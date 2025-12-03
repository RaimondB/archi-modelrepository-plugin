# GitHub Copilot Instructions for coArchi

## Project Overview

This is the coArchi plugin for Archi - a collaboration plugin that uses Git to store ArchiMate models in GRAFICO format (Git fRiendly Archi FIle COllection).

## Performance-Critical Code

When working on `GraficoModelExporter.java` or `GraficoModelImporter.java`, follow the guidelines in:

**[PERFORMANCE_ARCHITECTURE.md](../org.archicontribs.modelrepository/src/org/archicontribs/modelrepository/grafico/PERFORMANCE_ARCHITECTURE.md)**

### Key Requirements

1. **Async File I/O**: Use `AsynchronousFileChannel` for all file operations
2. **Separate Executors**: 
   - `ForkJoinPool(availableProcessors())` for CPU work (XML serialization, SHA-256 hashing)
   - Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) for I/O operations
3. **Batch Operations**: Group 100 files per `CompletableFuture` to reduce overhead (30,000 files → 300 futures)
4. **Progress Reporting**: Use `ThrottledProgressReporter` for time-based UI updates (every 250ms, not per-file)
5. **Memory**: Use SHA-256 hashes (32 bytes) instead of caching full file contents

### ⚠️ Critical: Proper Async Pipelining

**NEVER call `.join()` immediately after an async operation** - this blocks the thread and defeats the purpose of async I/O.

```java
// ❌ WRONG: Blocks immediately after async call - defeats async purpose!
for (File file : files) {
    byte[] bytes = readFileAsync(file).join();  // BLOCKS HERE!
    processBytes(bytes);
}

// ❌ WRONG: .join() inside batch still blocks the executor thread
CompletableFuture.runAsync(() -> {
    for (File file : batch) {
        byte[] bytes = readFileAsync(file).join();  // STILL BLOCKS!
        processBytes(bytes);
    }
}, executor);

// ✅ CORRECT: Chain futures - I/O completes, then CPU work runs automatically
List<CompletableFuture<Void>> futures = new ArrayList<>();
for (File file : files) {
    CompletableFuture<Void> future = readFileAsync(file)           // Async I/O
        .thenApplyAsync(bytes -> cpuWork(bytes), cpuExecutor)      // CPU pool
        .thenAccept(result -> store(result));                       // Quick store
    futures.add(future);
}
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();  // Wait at END only
```

**Key chaining methods:**
- `thenApplyAsync(fn, executor)` - Transform result, run on specified executor
- `thenComposeAsync(fn, executor)` - Chain another `CompletableFuture` (for async writes)
- `thenAccept(fn)` - Consume result (quick operations, any thread)
- `CompletableFuture.allOf(...).join()` - Final synchronization point ONLY

### ⚠️ Critical: TRUE Batching vs Fake Batching

Creating 30,000 `CompletableFuture` objects causes significant overhead. Use TRUE batching where **one future handles multiple files**.

```java
// ❌ WRONG: "Fake batching" - still creates 30,000 futures!
for (int i = 0; i < files.size(); i += BATCH_SIZE) {
    List<File> batch = files.subList(i, Math.min(i + BATCH_SIZE, files.size()));
    for (File file : batch) {  // <-- Inner loop creates one future per file!
        futures.add(CompletableFuture.runAsync(() -> process(file), executor));
    }
}
// Result: 30,000 futures, NOT 300!

// ✅ CORRECT: TRUE batching - one future per batch
for (int i = 0; i < files.size(); i += BATCH_SIZE) {
    final List<File> batch = files.subList(i, Math.min(i + BATCH_SIZE, files.size()));
    
    // ONE future processes ALL files in the batch
    CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
        for (File file : batch) {
            process(file);  // Sequential within batch, parallel across batches
        }
    }, executor);
    
    futures.add(batchFuture);
}
// Result: 300 futures for 30,000 files!
```

**Why this matters:**
- Each `CompletableFuture` allocates ~200 bytes of object overhead
- 30,000 futures = 6MB+ of object allocations, causing GC pressure
- ForkJoinPool work-stealing is more efficient with fewer, larger tasks

### Code Pattern

```java
private static final int BATCH_SIZE = 100;

// Batch files to reduce CompletableFuture overhead
for (int i = 0; i < files.size(); i += BATCH_SIZE) {
    List<File> batch = files.subList(i, Math.min(i + BATCH_SIZE, files.size()));
    futures.add(CompletableFuture.runAsync(() -> processBatch(batch), executor));
}
```

### ✅ Combined Pattern: TRUE Batching + Async I/O

For maximum performance, combine TRUE batching (one future per batch) with async I/O (non-blocking within batch):

```java
// ONE outer future per batch, async I/O for all files within batch
CompletableFuture<Void> batchFuture = CompletableFuture.supplyAsync(() -> {
    // Start all async reads for this batch (non-blocking)
    List<CompletableFuture<Void>> batchReads = new ArrayList<>();
    for (File file : batch) {
        CompletableFuture<Void> readFuture = readFileAsync(file)    // Async I/O
            .thenAcceptAsync(bytes -> {                              // CPU work
                if (bytes != null) {
                    byte[] hash = computeHash(bytes);
                    hashCache.put(file, hash);
                }
            }, cpuExecutor);
        batchReads.add(readFuture);
    }
    // Return future that completes when all batch reads done
    return CompletableFuture.allOf(batchReads.toArray(new CompletableFuture[0]));
}, cpuExecutor).thenCompose(f -> f);  // Flatten nested future

futures.add(batchFuture);
```

**Key insight**: The outer batch future limits total futures to ~300, while inner async I/O keeps disk busy without blocking threads.

## Eclipse Plugin Development

- This is an Eclipse RCP/OSGi plugin
- Uses EMF (Eclipse Modeling Framework) for model handling
- JGit for Git operations
- Minimum Java version: 21 (for virtual threads)

## Testing

Tests are in `org.archicontribs.modelrepository.tests`. Run with JUnit 5.

## Performance Measurement & Debugging

### Disk Cache Effects

The OS filesystem cache makes second runs appear 10-100x faster. To measure true cold-disk performance:

**Windows (PowerShell as Admin):**
```powershell
# Clear standby list (cached files) - requires RAMMap from Sysinternals
# Or use this built-in approach:
Write-VolumeCache C:

# Alternative: Use RAMMap.exe from Sysinternals
# RAMMap.exe /E  (empties standby list)
```

**Programmatic approach in Java:**
```java
// Force garbage collection and pause to let disk cache age out
System.gc();
Thread.sleep(5000);

// Read a large unrelated file to flush cache (crude but effective)
// Or use ProcessBuilder to call system cache-clear commands
```

**Best practice:** Run benchmark 3+ times, discard first run (warmup), report median of remaining runs.

### Potential Slowdown Causes

1. **File Handle Exhaustion**: Opening 30,000 `AsynchronousFileChannel`s simultaneously can exhaust OS file handles
   - **Symptom**: Slowdown as file count increases, then errors
   - **Fix**: Limit concurrent open channels (use semaphore or bounded executor)

2. **ConcurrentHashMap Contention**: High contention on `existingHashCache` with 30,000 concurrent puts
   - **Symptom**: CPU spinning, poor scaling beyond 4-8 cores  
   - **Fix**: Use `ConcurrentHashMap` with higher initial capacity: `new ConcurrentHashMap<>(fileCount, 0.75f, cpuThreads)`

3. **ByteBuffer Allocation Pressure**: Allocating 30,000 ByteBuffers causes GC pressure
   - **Symptom**: GC pauses, increasing latency over time
   - **Fix**: Use buffer pooling or direct buffers for large files

4. **Too Many CompletableFutures**: Even with batching, 30,000 futures = 30,000 object allocations
   - **Symptom**: Memory pressure, GC pauses
   - **Fix**: True batching where one future handles multiple files

5. **Progress Monitor Contention**: `AtomicInteger.incrementAndGet()` 30,000 times causes cache line bouncing
   - **Symptom**: Poor multi-core scaling
   - **Fix**: Use `LongAdder` instead of `AtomicInteger` for counters

6. **UI Thread Synchronization**: Calling `progress.subTask()` or `progress.worked()` too frequently blocks worker threads
   - **Symptom**: Progress dialog freezes, overall slowdown
   - **Fix**: Use `ThrottledProgressReporter` for time-based throttling (every 250ms max)

### ThrottledProgressReporter Pattern

Use `ThrottledProgressReporter` to minimize UI thread synchronization overhead:

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

**Key benefits:**
- Uses `LongAdder` for lock-free counting (no cache-line bouncing)
- Time-based throttling: updates UI at most every 250ms
- Count-based throttling: updates only after 2000 files processed
- Double-checked locking: only one thread updates UI at a time
- Thread-safe for use in async completion handlers

### Recommended Instrumentation

Add timing to identify bottlenecks:

```java
long startHash = System.nanoTime();
// ... hash phase ...
long hashTime = System.nanoTime() - startHash;

long startWrite = System.nanoTime();
// ... write phase ...
long writeTime = System.nanoTime() - startWrite;

System.out.printf("Hash: %dms, Write: %dms%n", 
    hashTime / 1_000_000, writeTime / 1_000_000);
```

### File Handle Limiting Pattern

```java
// Limit concurrent async file operations to prevent handle exhaustion
private static final int MAX_CONCURRENT_FILES = 256;
private final Semaphore fileHandleSemaphore = new Semaphore(MAX_CONCURRENT_FILES);

private CompletableFuture<byte[]> readFileAsyncLimited(File file) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            fileHandleSemaphore.acquire();
            try {
                return readFileAsyncInternal(file).join();
            } finally {
                fileHandleSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    });
}
```
