# Grafico I/O Performance Architecture

## Overview

This document defines the architectural guidelines for optimizing file I/O operations in `GraficoModelExporter` and `GraficoModelImporter`. These classes handle ~30,000 XML files averaging 8KB each (range: 2KB-40KB).

## Key Principles

### 1. Async File I/O (Mandatory)

**All file operations MUST use `AsynchronousFileChannel`** for true non-blocking I/O.

```java
// CORRECT: Non-blocking async I/O
AsynchronousFileChannel channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
    @Override
    public void completed(Integer bytesRead, ByteBuffer buf) {
        // Handle completion
    }
    @Override
    public void failed(Throwable exc, ByteBuffer buf) {
        // Handle failure
    }
});

// INCORRECT: Blocks a thread while waiting for disk
Files.readAllBytes(path);  // DON'T USE
new FileInputStream(file); // DON'T USE
```

**Rationale**: With 30,000 files, blocking I/O would require thousands of threads or serialize operations. Async I/O allows the OS to batch and optimize disk operations while not blocking any Java threads.

### 2. Separate Executors for I/O vs CPU Work

| Work Type | Executor | Sizing | Examples |
|-----------|----------|--------|----------|
| **I/O-bound** | Virtual threads or async callbacks | Unlimited (virtual) | File reads, file writes |
| **CPU-bound** | `ForkJoinPool` | `Runtime.getRuntime().availableProcessors()` | XML serialization, SHA-256 hashing, XML parsing |

```java
// CPU executor - sized to CPU cores
ForkJoinPool cpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

// I/O handled by async callbacks (no executor needed) or virtual threads for writes
ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

**Rationale**: CPU-bound work benefits from parallelism up to the number of cores. Beyond that, context switching overhead reduces throughput. I/O-bound work can scale much higher since threads spend most time waiting.

### 3. Batch Operations to Reduce Sync Overhead

**CRITICAL**: With 30,000 files, creating 30,000 `CompletableFuture` objects creates significant overhead. Use batching.

```java
// CORRECT: Batch files into groups of 100
private static final int BATCH_SIZE = 100;

List<List<File>> batches = new ArrayList<>();
for (int i = 0; i < files.size(); i += BATCH_SIZE) {
    batches.add(files.subList(i, Math.min(i + BATCH_SIZE, files.size())));
}

// Only 300 futures instead of 30,000
List<CompletableFuture<Void>> futures = batches.stream()
    .map(batch -> CompletableFuture.runAsync(() -> processBatch(batch), executor))
    .collect(Collectors.toList());
```

**Batch Size Recommendations**:
- **File reading/hashing**: 100 files per batch (I/O dominates)
- **XML serialization + write**: 50-100 files per batch (mixed I/O and CPU)
- **Progress reporting**: Every 1000 files (avoid UI thread overhead)

### 4. Buffer Sizing

```java
// Optimal buffer size for modern SSDs and file system block sizes
private static final int BUFFER_SIZE = 64 * 1024; // 64KB

// For ByteBuffer allocation, use file size when known
ByteBuffer buffer = ByteBuffer.allocate((int) file.length());
```

**Rationale**: 64KB aligns with typical SSD page sizes and file system read-ahead. Smaller buffers cause more syscalls; larger buffers waste memory with 30,000 files.

### 5. Memory Management

With 30,000 files × 8KB average = ~240MB of data, memory management is critical:

```java
// CORRECT: Process and release in batches
for (List<File> batch : batches) {
    Map<File, byte[]> batchData = readBatch(batch);
    processBatch(batchData);
    // batchData goes out of scope, eligible for GC
}

// INCORRECT: Load all files into memory
Map<File, byte[]> allData = new HashMap<>(); // 240MB+ in heap!
for (File file : files) {
    allData.put(file, Files.readAllBytes(file.toPath()));
}
```

### 6. Hash-Based Change Detection with DirCache

For git repositories, use the DirCache (git index) which already contains file hashes:

```java
// ✅ BEST: Use DirCache for git repos - no disk I/O needed!
// The git index already stores SHA-1/SHA-256 hashes of all tracked files.
if (fDirCache != null) {
    ObjectId existingHash = getHashFromDirCache(file);  // O(1) lookup
    ObjectId newHash = fObjectInserter.idFor(Constants.OBJ_BLOB, newContent);  // CPU-only
    return !existingHash.equals(newHash);
}

// ✅ GOOD: Compute hashes for non-git folders (32 bytes per file)
Map<File, byte[]> hashCache = new ConcurrentHashMap<>();
private byte[] computeHash(byte[] data) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(data);
}
```

**DirCache Benefits:**
- Zero disk I/O for unchanged files (typical export has 95%+ unchanged)
- Hash computation is ~100x faster than file read on cold cache
- Uses correct hash algorithm automatically (SHA-1 or SHA-256)
- Leverages git's existing index data structure

**IMPORTANT**: Use `ObjectInserter.idFor()` for hashing, not `MessageDigest`:
```java
// ✅ CORRECT: Auto-detects repository's hash algorithm
ObjectInserter inserter = repository.newObjectInserter();
ObjectId hash = inserter.idFor(Constants.OBJ_BLOB, content);

// ❌ WRONG: May not match repository's algorithm
MessageDigest md = MessageDigest.getInstance("SHA-1");
```

### 6b. DirCache for Import: Bulk Parallel File Loading

After export optimization leaves the disk cache cold, import must read all files from disk. Use DirCache to enable bulk parallel loading with **overlapped I/O and model building**:

```java
// ✅ FAST: Use DirCache for file discovery (no disk I/O for listing)
List<DirCacheFileEntry> allEntries = collectFilesFromDirCache();

// Categorize: folder.xml files (structure) vs element files (content)
List<DirCacheFileEntry> folderXmlFiles = allEntries.stream()
    .filter(e -> e.isFolderXml())
    .sorted((a, b) -> a.getDepth() - b.getDepth())  // Parents first
    .collect(toList());

// Phase 1: Read ALL folder.xml files in parallel, build hierarchy
CountDownLatch folderLatch = new CountDownLatch(folderXmlFiles.size());
for (DirCacheFileEntry entry : folderXmlFiles) {
    readAndParseDirect(entry.absolutePath(), folderContents, folderLatch);
}
folderLatch.await();
// Build folder hierarchy from parsed data (sequential, data in memory)

// Phase 2+3 OVERLAPPED: Producer/Consumer pattern
BlockingQueue<ElementWithFolder> queue = new LinkedBlockingQueue<>();
AtomicInteger remainingElements = new AtomicInteger(elementFiles.size());

// Start consumer FIRST (single-threaded for EMF safety)
Thread consumerThread = new Thread(() -> {
    while (remainingElements.get() > 0 || !queue.isEmpty()) {
        ElementWithFolder item = queue.poll(50, TimeUnit.MILLISECONDS);
        if (item != null) {
            IFolder parent = fFolderPathLookup.get(item.folderPath());
            parent.getElements().add(item.element());
            remainingElements.decrementAndGet();
        }
    }
});
consumerThread.start();

// Start ALL element reads (producers) - queue as parsed
for (DirCacheFileEntry entry : elementFiles) {
    readParseAndQueueElement(entry, queue, remainingElements, ...);
}

consumerThread.join();  // Wait for consumer to finish
```

**Overlapped Timeline (maximum parallelism):**
```
Thread 1 (I/O):      [read file A][read file B][read file C]...
Thread 2 (CPU):           [parse A]    [parse B]    [parse C]...
Consumer (EMF):               [add A]      [add B]      [add C]...
                    ←————— Maximum overlap, no waiting ——————→
```

**Import-Specific Benefits:**
- Zero disk I/O for directory listing (~30 folder scans → 0)
- ALL file reads start simultaneously (maximum I/O parallelism)
- Pre-flight folder creation ensures parents exist before children
- Single-threaded model building maintains EMF thread safety
- **Overlapped I/O and model building** - elements added as soon as parsed, no waiting for all reads

### 7. Progress Reporting

**Use `ThrottledProgressReporter` for time-based throttling** to minimize UI thread contention:

```java
// Create throttled reporter - updates at most every 250ms or every 2000 files
ThrottledProgressReporter reporter = new ThrottledProgressReporter(
    progress.split(totalFiles), totalFiles);

// In async callbacks (called from many threads):
reporter.incrementAndMaybeReport(
    count -> String.format("Processing %d of %d files...", count, totalFiles));

// At end - ensures final progress is reported:
reporter.finish(null);
```

**Why not per-file or even per-1000-files updates?**

- `progress.subTask()` synchronizes with the UI thread, causing blocking
- With 30,000 files across 8 CPU cores, many threads contend for UI updates
- Time-based throttling (250ms) provides smooth UI updates without contention
- `LongAdder` provides lock-free counting with minimal cache-line bouncing

**ThrottledProgressReporter benefits:**

- Uses `LongAdder` for lock-free counting (vs `AtomicInteger` cache-line bouncing)
- Time-based throttling: updates UI at most every 250ms
- Count-based throttling: updates only after 2000 files processed  
- Double-checked locking: only one thread updates UI at a time
- Thread-safe for use in async completion handlers

## Implementation Patterns

### Pattern A: Async Read → CPU Process → Result

```java
CompletableFuture<Result> processFile(File file) {
    return readFileAsync(file)                              // Async I/O
        .thenApplyAsync(bytes -> cpuIntensiveWork(bytes), cpuExecutor)  // CPU pool
        .thenApply(result -> storeResult(result));          // Quick, any thread
}
```

### Pattern B: Batched Processing with Progress

```java
void processAllFiles(List<File> files, IProgressMonitor monitor) {
    AtomicInteger completed = new AtomicInteger(0);
    int total = files.size();
    
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < files.size(); i += BATCH_SIZE) {
        List<File> batch = files.subList(i, Math.min(i + BATCH_SIZE, files.size()));
        
        futures.add(CompletableFuture.runAsync(() -> {
            for (File file : batch) {
                processFile(file);
                
                int count = completed.incrementAndGet();
                if (count % 1000 == 0) {
                    monitor.subTask(String.format("Processed %d of %d", count, total));
                }
            }
        }, cpuExecutor));
    }
    
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

### Pattern C: Async Write with Virtual Threads

```java
void writeFiles(List<WriteTask> tasks, ExecutorService ioExecutor) {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < tasks.size(); i += BATCH_SIZE) {
        List<WriteTask> batch = tasks.subList(i, Math.min(i + BATCH_SIZE, tasks.size()));
        
        futures.add(CompletableFuture.runAsync(() -> {
            for (WriteTask task : batch) {
                writeFileAsync(task.file, task.content);
            }
        }, ioExecutor));
    }
    
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

### Pattern D: Direct Async I/O with CountDownLatch (Preferred for Bulk Operations)

**Use this pattern instead of wrapping `AsynchronousFileChannel` operations in `CompletableFuture`
when you have many I/O operations with simple completion actions.**

```java
void writeFilesDirect(List<WriteTask> tasks) throws IOException {
    CountDownLatch latch = new CountDownLatch(tasks.size());
    List<IOException> exceptions = Collections.synchronizedList(new ArrayList<>());
    
    for (WriteTask task : tasks) {
        writeFileAsyncDirect(task.file, task.content, latch, exceptions);
    }
    
    latch.await();  // Wait for all to complete
    
    if (!exceptions.isEmpty()) {
        throw exceptions.get(0);
    }
}

// Direct async I/O - no CompletableFuture wrapper
private void writeFileAsyncDirect(File file, byte[] data, 
        CountDownLatch latch, List<IOException> exceptions) {
    try {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        AsynchronousFileChannel channel = AsynchronousFileChannel.open(
            file.toPath(), CREATE, WRITE, TRUNCATE_EXISTING);
        
        channel.write(buffer, 0, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer result, Void attachment) {
                try { channel.close(); } catch (IOException e) { exceptions.add(e); }
                finally { latch.countDown(); }
            }
            
            @Override
            public void failed(Throwable exc, Void attachment) {
                try { channel.close(); } catch (IOException e) { /* ignore */ }
                exceptions.add(exc instanceof IOException ? (IOException) exc : new IOException(exc));
                latch.countDown();
            }
        });
    } catch (IOException e) {
        exceptions.add(e);
        latch.countDown();
    }
}
```

**Why this is more efficient than CompletableFuture wrapping:**

| Aspect | CompletableFuture Wrapper | Direct CountDownLatch |
|--------|---------------------------|------------------------|
| Object allocation | ~200 bytes per future | Single latch for all ops |
| GC pressure | 30,000 files = 6MB+ | Minimal |
| Synchronization | State machine overhead | Simple atomic decrement |
| Callback path | Future completion → thenXxx chain | Direct to latch.countDown() |

**When to use this pattern:**
- Firing many async I/O operations of the same type (reads or writes)
- Completion action is simple (store result, collect errors)
- No need for async chaining (thenApply, thenCompose, etc.)

**When to still use CompletableFuture:**
- Need async chaining (read → CPU process → write)
- Need to combine results from multiple async sources
- Complex error handling with recovery

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| File read throughput | >100 MB/s | Should saturate SSD |
| Files processed/sec | >5,000 | For hash comparison |
| Memory overhead | <50MB | Excluding file content in transit |
| CPU utilization | >80% | During serialization phase |
| Future object count | <500 | Use batching |

## Executor Lifecycle

```java
ForkJoinPool cpuExecutor = null;
ExecutorService ioExecutor = null;

try {
    cpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // ... do work ...
    
} finally {
    if (cpuExecutor != null) cpuExecutor.shutdown();
    if (ioExecutor != null) ioExecutor.shutdown();
}
```

## Cancellation Support

Check for cancellation between batches, not within:

```java
for (List<File> batch : batches) {
    if (monitor.isCanceled()) {
        executor.shutdownNow();
        return;
    }
    processBatch(batch);
}
```

## File Size Considerations

| File Size | Count (est.) | Strategy |
|-----------|--------------|----------|
| <4KB | ~5,000 | Batch aggressively (100+) |
| 4-16KB | ~20,000 | Standard batching (100) |
| >16KB | ~5,000 | Smaller batches (50) or individual |

## Summary Checklist

- [ ] All file reads use `AsynchronousFileChannel`
- [ ] All file writes use `AsynchronousFileChannel` or virtual threads
- [ ] CPU work (serialization, hashing) uses `ForkJoinPool` sized to CPU cores
- [ ] Files batched in groups of 100 to reduce `CompletableFuture` overhead
- [ ] Progress uses `ThrottledProgressReporter` for time-based updates (every 250ms)
- [ ] DirCache used for git repos (no disk read needed for hash comparison)
- [ ] `ObjectInserter.idFor()` used for hashing (auto-detects SHA-1 vs SHA-256)
- [ ] Fallback to file read for non-git folders
- [ ] 64KB buffer size for I/O operations
- [ ] Executors properly shut down in finally blocks
- [ ] DirCache resources (Repository, ObjectInserter) cleaned up in finally blocks
- [ ] Cancellation checked between batches

---

## Import Performance: Experimental Results & Lessons Learned

### Test Environment
- **Files**: ~26,680 element files + ~1,560 folder.xml files = ~28,240 total
- **Average file size**: ~8KB (range: 2-40KB)
- **Total data**: ~220MB
- **Hardware**: 20 CPU cores, NVMe SSD (but testing with cold disk cache)
- **Cold cache**: Achieved by running export first (uses DirCache hashing, no file reads)

### Key Observation: CPU Saturation, NOT Disk Saturation

**Critical finding**: In all tests, CPU was saturated but disk was NOT. This indicates the bottleneck is **not disk I/O** but rather:
- XML parsing (EMF/SAX)
- Synchronization overhead
- Thread scheduling

### Approach Comparison Matrix

| Approach | Cold Cache Time | Items/sec | Peak Parallelism | Notes |
|----------|-----------------|-----------|------------------|-------|
| BufferedInputStream + VirtualThreads (individual) | ~21s | ~1,264 | 966 batches started, 248 effective | Virtual threads pinned by synchronized BufferedInputStream |
| AsynchronousFileChannel (26K simultaneous) | ~45s | ~589 | 555 peak | File handle exhaustion (Windows limit ~500) |
| Files.readAllBytes + VirtualThreads (1000 batches) | ~20s | ~1,400 | 273 batches concurrent | Still limited by something |
| ForkJoinPool (20 threads) | ~22s | ~1,191 | 20 | Limited to CPU core count |
| Warm cache (any approach) | ~1.8s | ~15,000 | - | OS cache eliminates I/O wait |

### Key Lessons Learned

#### 1. Virtual Threads Don't Help with Synchronized I/O

**Problem**: Virtual threads promise cheap concurrency, but they "pin" to carrier threads when executing synchronized code.

```java
// ❌ PINS virtual thread to carrier thread
BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(path));
bis.read(buffer);  // synchronized internally!

// ❌ ALSO PINS - Files.readAllBytes uses FileInputStream internally  
byte[] data = Files.readAllBytes(path);  // Has synchronized blocks

// ✅ TRUE async - doesn't pin
AsynchronousFileChannel channel = AsynchronousFileChannel.open(path);
channel.read(buffer, 0, callback);  // Returns immediately
```

**Result**: With 20 carrier threads, only ~20 virtual threads can make progress at a time, regardless of how many are created.

#### 2. AsynchronousFileChannel Has Limits

**Problem**: Opening 26,680 `AsynchronousFileChannel` simultaneously exhausts OS file handles.

```
peakConcurrentReads = 555 (of 26,680 files)  ← Windows file handle limit!
```

**Symptoms**:
- `peakConcurrent` much lower than files submitted
- Massive cumulative time (files waiting in OS queue)
- Performance worse than synchronous approach

**Solution**: Limit concurrent async operations with a semaphore, OR use batched synchronous I/O.

#### 3. CPU is the Bottleneck, Not Disk

**Evidence**:
- Disk utilization never hits 100% in any test
- CPU is always saturated
- Per-file time (~177ms) is much higher than expected disk latency (~5-10ms)

**Root causes**:
1. **SAXParser creation**: Each file creates a new SAXParser (expensive)
2. **EMF overhead**: Resource creation, feature lookups
3. **Synchronization**: HashMap, queue operations

**Mitigations applied**:
- `XMLParserPool` - Reuses SAXParsers (EMF built-in)
- `XML_NAME_TO_FEATURE_MAP` - Caches schema lookups
- `LinkedBlockingQueue` - Separate head/tail locks

#### 4. Effective Parallelism is Limited to ~250

Regardless of approach (virtual threads, async I/O, batching), we consistently see:
```
effectiveParallelism ≈ 244-273
```

This suggests a **fundamental limit** in either:
- JVM file I/O implementation
- OS file system driver
- EMF/SAX parser internals

### Testing Methodology: Isolate Each Component

To make progress, we need to test each component in isolation:

#### Test 1: Pure Disk I/O (No Parsing)
```java
// Measure raw file reading without any parsing
long start = System.nanoTime();
for (File file : files) {
    byte[] data = Files.readAllBytes(file.toPath());
}
long elapsed = System.nanoTime() - start;
// Expected: ~220MB at 500MB/s = 0.44s (SSD) or ~2s (HDD)
```

**Purpose**: Establish baseline disk throughput. If this is slow, disk is the bottleneck.

#### Test 2: Pure Parsing (No Disk I/O)
```java
// Pre-read all files to memory, then measure parsing only
Map<File, byte[]> preloaded = new HashMap<>();
for (File file : files) {
    preloaded.put(file, Files.readAllBytes(file.toPath()));
}
System.gc();  // Clear allocation pressure

long start = System.nanoTime();
for (Map.Entry<File, byte[]> entry : preloaded.entrySet()) {
    try (InputStream is = new ByteArrayInputStream(entry.getValue())) {
        GraficoResourceLoader.loadEObject(is);
    }
}
long elapsed = System.nanoTime() - start;
// This tells us: parsing cost without I/O
```

**Purpose**: Establish baseline parsing throughput. If this matches current performance, parsing is the bottleneck.

#### Test 3: Parallel Parsing (No Disk I/O, No EMF Model Building)
```java
// Same as Test 2, but parallel and without adding to model
ForkJoinPool pool = new ForkJoinPool(20);
pool.submit(() -> 
    preloaded.entrySet().parallelStream().forEach(entry -> {
        try (InputStream is = new ByteArrayInputStream(entry.getValue())) {
            GraficoResourceLoader.loadEObject(is);  // Parse only, discard result
        }
    })
).get();
```

**Purpose**: Test if parsing can parallelize. If not, SAXParser/EMF has internal synchronization.

#### Test 4: Consumer-Only (No Parsing, Pre-built Elements)
```java
// Measure just the model building
List<EObject> prebuiltElements = ...;  // Load once, reuse
long start = System.nanoTime();
for (EObject element : prebuiltElements) {
    folder.getElements().add(element);
}
long elapsed = System.nanoTime() - start;
```

**Purpose**: Verify consumer is not the bottleneck (current logs suggest it's not: 430K items/sec theoretical max).

### Current Architecture Decision (December 2025)

Based on extensive isolation testing, the **optimal approach** for cold cache is:

1. **CompletableFuture + ForkJoinPool(20)** with 1000 batches for file reading
2. **`Files.readAllBytes()` + `ByteArrayInputStream`** for I/O
3. **EMF XMLParserPool** for SAX parser reuse
4. **`LinkedBlockingQueue`** for producer/consumer pattern
5. **Single-threaded consumer** for EMF thread safety

This achieves **~20s on cold cache** (~1,322 files/sec for 26,679 files).

### Isolation Test Results (December 2025) - FINAL

**System Configuration:**
- Java 21.0.7 (OpenJDK 64-Bit Server VM)
- Windows 11, 20 CPU cores, 8GB heap
- ForkJoinPool parallelism: 19
- Test files: 26,679 files, 37.6 MB total (~1.4KB average)

#### Cold Cache I/O Comparison (CRITICAL FINDINGS)

| Test | Approach | Cold Cache Time | Rate | Key Insight |
|------|----------|-----------------|------|-------------|
| Sequential | `Files.readAllBytes()` loop | 95s | 279/s | Baseline - terrible |
| parallelStream | ForkJoinPool, no batching | 44.5s | 599/s | 2.1x speedup |
| Virtual Threads | 1000 batches | 64.5s | 413/s | SLOWER - thread pinning! |
| AsyncFileChannel | 200 concurrent handles | 62.8s | 425/s | Peak=22 handles only |
| **CompletableFuture + FJP** | **1000 batches, ForkJoinPool(20)** | **20.2s** | **1,322/s** | **WINNER - 4.7x speedup** |

#### Warm Cache I/O Comparison

| Test | Time | Rate |
|------|------|------|
| Sequential | 0.68s | 39,473/s |
| ForkJoinPool parallelStream | 0.20s | 131,782/s |
| Virtual Threads | 0.26s | 104,440/s |

#### Parsing Performance (Pre-loaded Data)

| Test | Time | Rate | Notes |
|------|------|------|-------|
| Single-threaded parsing | 2.79s | 9,557/s | EMF XMLParserPool enabled |
| Parallel parsing (20 threads) | 0.82s | 32,379/s | 3.4x speedup (not 20x - contention) |

#### Combined I/O + Parsing (Warm Cache)

| Test | Time | Rate |
|------|------|------|
| Batched I/O + Parsing | 1.60s | 16,663/s |

### Key Learnings

#### 1. Virtual Threads Are SLOWER for Cold Cache File I/O

**Why?** `Files.readAllBytes()` uses `FileInputStream` which has synchronized methods. When a virtual thread hits synchronized code, it **pins to the carrier thread**, eliminating the benefit of lightweight threading.

```
Virtual Threads (1000): 64.5s - PINS on synchronized I/O
ForkJoinPool (20):      44.5s - No pinning overhead
CompletableFuture+FJP:  20.2s - Batching + work-stealing
```

#### 2. Batching is CRITICAL for Cold Cache

| Batching | Cold Cache Time |
|----------|-----------------|
| No batching (parallelStream) | 44.5s |
| 1000 batches (CompletableFuture) | 20.2s |

**Why?** ForkJoinPool work-stealing is more efficient with fewer, larger tasks. Each batch processes ~27 files sequentially, reducing:
- Task scheduling overhead
- File handle contention
- Memory allocation pressure

#### 3. AsyncFileChannel is NOT Faster

Despite being "truly async", `AsynchronousFileChannel` with semaphore limiting achieved only:
- Peak concurrent handles: 22 (not 200!)
- Time: 62.8s (slower than ForkJoinPool)

**Why?** The semaphore acquisition becomes a bottleneck, and the async callback overhead adds latency.

#### 4. The 20s Floor - What's Blocking Further Improvement?

With 26,679 files at 20.2s = **1,322 files/sec**.

**Theoretical limits:**

| Component | Time | Rate | Notes |
|-----------|------|------|-------|
| Pure disk read (cold) | 20.2s | 1,322/s | **CURRENT BOTTLENECK** |
| Parsing (20 threads) | 0.82s | 32,379/s | ~25x headroom |
| Warm cache I/O | 0.20s | 131,782/s | ~100x headroom |

**What's limiting disk throughput to 1,322 files/sec?**

1. **Per-file overhead**: Each file open/read/close has ~0.75ms overhead on cold cache
   - 26,679 files × 0.75ms = 20s
   - This is the **filesystem metadata overhead**, not data transfer

2. **NTFS metadata lookups**: Each file requires:
   - MFT (Master File Table) lookup
   - Directory entry traversal
   - Security descriptor check
   - Possible antivirus scan

3. **OS file cache population**: First read of each file must:
   - Read from physical disk
   - Allocate cache pages
   - Copy to user space

4. **20 concurrent reads max**: ForkJoinPool(20) can only have 20 files in-flight at once. If each file takes 1ms to read:
   - 26,679 files / 20 threads × 1ms = 1.3s (theoretical)
   - But cold cache takes 20s = 15ms average per file including overhead

### Strategies to Go Below 20s

#### Strategy 1: Reduce File Count (HIGHEST IMPACT)

**Problem**: 26,679 small files = 26,679 metadata operations

**Solution**: Bundle multiple elements per file
- 100 elements per file → 267 files → ~0.2s metadata overhead
- Requires GRAFICO format changes

#### Strategy 2: Pre-warm Disk Cache During Export

**Problem**: Export uses DirCache (no file reads), leaving cache cold

**Solution**: Background thread reads files during export
```java
// During export, spawn cache-warming thread
executor.submit(() -> {
    for (File file : allFiles) {
        Files.readAllBytes(file.toPath()); // Just read, discard
    }
});
```
- Next import would hit warm cache (~1.6s instead of 20s)

#### Strategy 3: Memory-Mapped Files

**Problem**: Each `Files.readAllBytes()` allocates a new byte array

**Solution**: Use `MappedByteBuffer` for zero-copy reads
```java
try (FileChannel channel = FileChannel.open(path, READ)) {
    MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
    // Parse directly from buffer
}
```
- Reduces memory allocation pressure
- May improve OS read-ahead for sequential access

#### Strategy 4: Increase Batch Concurrency

**Current**: 1000 batches of ~27 files each, 20 threads

**Test**: Reduce batch size to increase parallelism
- 2000 batches of ~13 files → more concurrent I/O requests
- Trade-off: more CompletableFuture overhead

#### Strategy 5: Windows Defender Exclusion

**Problem**: Antivirus may scan each file on first access

**Solution**: Add repository folder to Windows Defender exclusions
- Could provide significant speedup for cold cache

#### Strategy 6: SSD/NVMe Optimization

**Problem**: Many small random reads are worst case for any storage

**Current**: 1.9 MB/s throughput (vs theoretical 500+ MB/s)

**Solutions**:
- Ensure files are on NVMe, not spinning disk
- Check for disk fragmentation
- Verify TRIM is enabled

### Recommended Implementation

Based on testing, the optimal import implementation is:

```java
// Use ForkJoinPool (NOT virtual threads) for cold cache I/O
ForkJoinPool cpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

// 1000 batches for optimal balance
int batchCount = 1000;
int batchSize = (files.size() + batchCount - 1) / batchCount;

List<CompletableFuture<Void>> futures = new ArrayList<>();
for (int i = 0; i < files.size(); i += batchSize) {
    final int start = i;
    final int end = Math.min(i + batchSize, files.size());
    
    futures.add(CompletableFuture.runAsync(() -> {
        for (int j = start; j < end; j++) {
            byte[] data = Files.readAllBytes(files.get(j).toPath());
            EObject element = parseElement(data);
            queue.put(new ParsedElement(element, folder));
        }
    }, cpuExecutor));
}

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

**Key principles:**
1. **ForkJoinPool** for I/O (not virtual threads - they pin)
2. **1000 batches** for optimal work-stealing
3. **Sequential within batch** to reduce contention
4. **Producer/consumer queue** for EMF thread safety

