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

### 6. Hash-Based Change Detection

Use SHA-256 hashes (32 bytes) instead of caching full file contents:

```java
// 30,000 files × 32 bytes = 960KB (vs 240MB for full content)
Map<File, byte[]> hashCache = new ConcurrentHashMap<>();

private byte[] computeHash(byte[] data) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(data);
}
```

### 7. Progress Reporting

Minimize UI thread updates:

```java
AtomicInteger processed = new AtomicInteger(0);
final int total = files.size();

// Report every 1000 files, not every file
int count = processed.incrementAndGet();
if (count % 1000 == 0 || count == total) {
    progress.subTask(String.format("Processing %d of %d files...", count, total));
}
```

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
- [ ] Progress reported every 1000 files, not per-file
- [ ] SHA-256 hashes used instead of full content caching
- [ ] 64KB buffer size for I/O operations
- [ ] Executors properly shut down in finally blocks
- [ ] Cancellation checked between batches
