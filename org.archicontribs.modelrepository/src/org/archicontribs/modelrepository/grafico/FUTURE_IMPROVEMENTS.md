# Future Performance Improvements for Grafico I/O

This document tracks identified optimization opportunities for `GraficoModelExporter` and `GraficoModelImporter`. Items are prioritized by impact and effort.

## Priority Matrix

| Priority | Impact | Effort | Items |
|----------|--------|--------|-------|
| **P1** | High | Low | #2, #7, #8, #12 |
| **P2** | High | Medium | #4, #5, #6 |
| **P3** | Medium | Low | #9, #14, #15 |
| **P4** | Medium | Medium | #1, #3, #10, #11, #13 |
| **P5** | Lower | Low | #16, #17, #18, #19, #20 |

---

## High Impact Optimizations

### 1. Avoid Serialization When Content Unchanged (Exporter)
**Status:** Not Started  
**Impact:** High - Skip CPU-intensive XML serialization entirely  
**Effort:** Medium  

Currently, every element is serialized to XML before comparing hashes. If we maintain a hash of the EMF object state (or track dirty flags), we could skip serialization for unchanged elements.

**Implementation approach:**
- Track EMF model change notifications
- Maintain element-level dirty flags
- Only serialize elements marked as dirty

---

### 2. ThreadLocal MessageDigest (Exporter)
**Status:** Not Started  
**Impact:** High - Eliminate lock contention on hash computation  
**Effort:** Low  

`MessageDigest.getInstance("SHA-256")` is called per-file. Use `ThreadLocal<MessageDigest>` to reuse instances:

```java
private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
    try {
        return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
    }
});

private byte[] computeHash(byte[] data) {
    MessageDigest digest = SHA256_DIGEST.get();
    digest.reset();
    return digest.digest(data);
}
```

---

### 3. Pre-compute Element-to-File Mapping (Exporter)
**Status:** Not Started  
**Impact:** High - Reduce repeated path calculations  
**Effort:** Medium  

`createElementFile()` is called twice per element (once for hash check, once for write). Cache the `File` objects:

```java
Map<String, File> elementFileCache = new HashMap<>(elementCount);
```

---

### 4. Apply CountDownLatch Pattern to Importer Model Loading
**Status:** Not Started  
**Impact:** High - Reduce overhead for 30,000 file reads  
**Effort:** Medium  

The `loadFolder()` method still uses CompletableFuture batching. Apply the same direct async I/O pattern used in image loading:

```java
// Replace CompletableFuture batching with direct CountDownLatch pattern
CountDownLatch latch = new CountDownLatch(files.size());
for (File file : files) {
    readFileAsyncDirect(file, latch, results, exceptions);
}
latch.await();
```

---

### 5. Limit Concurrent File Handles (Both)
**Status:** Not Started  
**Impact:** High - Prevent file handle exhaustion  
**Effort:** Medium  

With 30,000 files, opening all `AsynchronousFileChannel`s simultaneously can exhaust OS file handles. Add a semaphore:

```java
private static final int MAX_CONCURRENT_FILES = 256;
private final Semaphore fileHandleSemaphore = new Semaphore(MAX_CONCURRENT_FILES);

// In async methods:
fileHandleSemaphore.acquire();
try {
    // ... async file operation ...
} finally {
    fileHandleSemaphore.release();  // In completion handler
}
```

---

### 6. Merge Hash Computation and Write into Single Pipeline (Exporter)
**Status:** Not Started  
**Impact:** High - Reduce memory pressure  
**Effort:** Medium  

Current flow: serialize → compute hash → compare → write if different

Proposed flow: serialize → compute hash → if different, write immediately (data still in memory)

This avoids re-serializing or caching the byte[] content.

---

### 7. Pre-size ConcurrentHashMap for Hash Cache (Exporter)
**Status:** Not Started  
**Impact:** Medium-High - Reduce rehashing overhead  
**Effort:** Low  

```java
// Current
Map<File, byte[]> existingHashCache = new ConcurrentHashMap<>();

// Improved - pre-size to expected capacity
int expectedFiles = 30000;
int cpuCount = Runtime.getRuntime().availableProcessors();
Map<File, byte[]> existingHashCache = new ConcurrentHashMap<>(expectedFiles, 0.75f, cpuCount);
```

---

### 8. Pre-size Results List (Importer)
**Status:** Not Started  
**Impact:** Medium-High - Reduce ArrayList resizing  
**Effort:** Low  

```java
// Current
List<IArchimateModelObject> allElements = new ArrayList<>();

// Improved
List<IArchimateModelObject> allElements = new ArrayList<>(expectedElementCount);
```

---

## Medium Impact Optimizations

### 9. Use LongAdder for Counters (Both)
**Status:** Not Started  
**Impact:** Medium - Better multi-core scaling for counters  
**Effort:** Low  

`ThrottledProgressReporter` already uses `LongAdder`, but verify any remaining `AtomicInteger` usage in hot paths.

---

### 10. ByteBuffer Pooling for Async I/O (Both)
**Status:** Not Started  
**Impact:** Medium - Reduce GC pressure from buffer allocation  
**Effort:** Medium  

With 30,000 files, allocating 30,000 `ByteBuffer` objects creates GC pressure. Use a pool:

```java
private static final int BUFFER_POOL_SIZE = 256;
private final BlockingQueue<ByteBuffer> bufferPool = new ArrayBlockingQueue<>(BUFFER_POOL_SIZE);

// Pre-populate pool
for (int i = 0; i < BUFFER_POOL_SIZE; i++) {
    bufferPool.offer(ByteBuffer.allocate(64 * 1024));
}

// Borrow and return pattern
ByteBuffer buffer = bufferPool.poll();
if (buffer == null) buffer = ByteBuffer.allocate(64 * 1024);
// ... use buffer ...
buffer.clear();
bufferPool.offer(buffer);
```

---

### 11. Parallel Directory Creation (Exporter)
**Status:** Not Started  
**Impact:** Medium - Speed up initial folder structure creation  
**Effort:** Medium  

`createElementFile()` creates directories synchronously. For deeply nested models, this adds latency.

---

### 12. Reuse XMLResource Save Options Map (Exporter)
**Status:** Not Started  
**Impact:** Medium - Reduce object allocation  
**Effort:** Low  

```java
// Current: new HashMap created per save
Map<String, Object> options = new HashMap<>();
options.put(XMLResource.OPTION_ENCODING, "UTF-8");

// Improved: static immutable map
private static final Map<String, Object> SAVE_OPTIONS = Map.of(
    XMLResource.OPTION_ENCODING, "UTF-8"
);
```

---

### 13. Streaming XML Serialization (Exporter)
**Status:** Not Started  
**Impact:** Medium - Reduce memory for large elements  
**Effort:** Medium  

Instead of serializing to `byte[]` then writing, stream directly to `AsynchronousFileChannel`:

```java
// Requires custom XMLResource output stream that wraps async channel
```

---

### 14. Lazy Image Loading (Importer)
**Status:** Not Started  
**Impact:** Medium - Speed up initial import  
**Effort:** Low  

Images are loaded eagerly but may not be immediately needed. Consider lazy loading on first access.

---

### 15. Skip Unchanged Folders During Cleanup (Exporter)
**Status:** Not Started  
**Impact:** Medium - Reduce directory traversal  
**Effort:** Low  

`cleanupObsoleteFiles()` traverses all directories. If we track which folders had changes, we can skip unchanged subtrees.

---

## Lower Impact Optimizations

### 16. Direct ByteBuffer for Small Files (Both)
**Status:** Not Started  
**Impact:** Lower - Slight performance gain for very small files  
**Effort:** Low  

For files < 4KB, use `ByteBuffer.allocateDirect()` to avoid one copy:

```java
ByteBuffer buffer = fileSize < 4096 
    ? ByteBuffer.allocateDirect((int) fileSize)
    : ByteBuffer.allocate((int) fileSize);
```

---

### 17. Batch Directory Existence Checks (Exporter)
**Status:** Not Started  
**Impact:** Lower - Reduce filesystem calls  
**Effort:** Low  

Before writing, batch check which directories exist to avoid redundant `mkdirs()` calls.

---

### 18. Use `Files.write()` Options for Atomic Writes (Exporter)
**Status:** Not Started  
**Impact:** Lower - Data integrity improvement  
**Effort:** Low  

Add `SYNC` option for critical files to ensure durability:

```java
Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.SYNC);
```

---

### 19. Cache Folder Type Detection (Importer)
**Status:** Not Started  
**Impact:** Lower - Minor optimization  
**Effort:** Low  

`loadFolder()` detects folder types repeatedly. Cache the detection result.

---

### 20. Profile Memory Allocation Hotspots (Both)
**Status:** Not Started  
**Impact:** Lower - Identifies unknown issues  
**Effort:** Low  

Use Java Flight Recorder to profile allocation hotspots during export/import of large models.

```powershell
java -XX:StartFlightRecording=duration=60s,filename=profile.jfr ...
```

---

## Implementation Notes

### Testing Approach
- Benchmark before/after each change with 30,000 file model
- Clear filesystem cache between runs for accurate cold-disk measurements
- Use `System.nanoTime()` for timing, `Runtime.getRuntime().freeMemory()` for memory

### Measurement Commands (Windows PowerShell as Admin)
```powershell
# Clear filesystem cache (requires RAMMap from Sysinternals)
.\RAMMap.exe /E

# Or use this PowerShell command
Write-VolumeCache C:
```

### Related Documentation
- [PERFORMANCE_ARCHITECTURE.md](./PERFORMANCE_ARCHITECTURE.md) - Core patterns and architecture
- [copilot-instructions.md](../../../.github/copilot-instructions.md) - Development guidelines

---

## Changelog

| Date | Item | Status | Notes |
|------|------|--------|-------|
| 2025-12-03 | Document created | N/A | Initial 20 optimization opportunities identified |
