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
