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
**Status:** ❌ Not Feasible  
**Impact:** N/A  
**Effort:** N/A  

~~Currently, every element is serialized to XML before comparing hashes. If we maintain a hash of the EMF object state (or track dirty flags), we could skip serialization for unchanged elements.~~

**Why not feasible:**
EMF objects are not tracked across exports - the exporter works on a **copy** of the model (`EcoreUtil.copy(fModel)`), so there's no persistent state to track changes. Each export starts fresh with new EMF object instances, making dirty-flag tracking impossible without significant architectural changes to the export process.

---

### 2. ThreadLocal MessageDigest (Exporter)
**Status:** ❌ No Longer Applicable  
**Impact:** N/A  
**Effort:** N/A  

~~`MessageDigest.getInstance("SHA-256")` is called per-file. Use `ThreadLocal<MessageDigest>` to reuse instances.~~

**Why not applicable:**
The merged pipeline (#6) eliminated SHA-256 hashing entirely. We now use direct `Arrays.equals()` byte comparison since both existing and new content are in memory simultaneously. No `MessageDigest` is used anymore.

---

### 3. Pre-compute Element-to-File Mapping (Exporter)
**Status:** ✅ Already Implemented  
**Impact:** N/A  
**Effort:** N/A  

~~`createElementFile()` is called twice per element (once for hash check, once for write). Cache the `File` objects.~~

**Why already implemented:**
The merged pipeline (#6) collects all `ResourceWriteTask` objects upfront, each containing the pre-computed `File`. The file path is calculated once and reused throughout the read → serialize → compare → write pipeline.

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
**Status:** ✅ Already Handled  
**Impact:** N/A  
**Effort:** N/A  

~~With 30,000 files, opening all `AsynchronousFileChannel`s simultaneously can exhaust OS file handles. Add a semaphore.~~

**Why already handled:**

1. **Batching limits concurrency**: We process 100 files per batch (`BATCH_SIZE = 100`)
2. **ForkJoinPool limits parallelism**: Only `availableProcessors()` batches run concurrently (~8 cores = ~800 max concurrent files)
3. **OS-level management**: `AsynchronousFileChannel` uses I/O Completion Ports (Windows) or internal thread pools (Linux/macOS) that inherently manage resources efficiently
4. **Sequential batch waits**: Each batch waits for its I/O to complete before the next batch's results are processed

With typical OS file handle limits of 4096+, our ~800 peak concurrent operations are well within safe bounds.

---

### 6. Merge Hash Computation and Write into Single Pipeline (Exporter)
**Status:** ✅ Completed (2025-12-03)  
**Impact:** High - Reduce memory pressure and CPU overhead  
**Effort:** Medium  

Previous flow: 
1. Read all existing files → compute hashes → store in `existingHashCache` (ConcurrentHashMap)
2. Serialize all → compute new hashes → compare with cached hash → write if different

New merged pipeline (per batch):
1. Read existing files async (parallel I/O)
2. Serialize resources (CPU)  
3. **Direct byte array comparison** - no hashing needed since both are in memory
4. Write immediately if different (async I/O) - content still in memory

Benefits:
- Avoids storing all hashes in `ConcurrentHashMap` (30,000 entries × 32 bytes + object overhead)
- **Eliminates SHA-256 computation entirely** (~2000 CPU cycles saved per file)
- Writes immediately while serialized content is still in memory
- Single pass through resources instead of two separate phases
- Better memory locality - data stays hot in CPU cache
- Removed `MessageDigest` and `NoSuchAlgorithmException` imports

---

### 7. Pre-size ConcurrentHashMap for Hash Cache (Exporter)
**Status:** ❌ No Longer Applicable  
**Impact:** N/A  
**Effort:** N/A  

This optimization is no longer relevant because the merged pipeline (#6) eliminated the hash cache entirely. 
Direct byte array comparison is used instead of SHA-256 hashing.

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
**Status:** ❌ Not Applicable  
**Impact:** N/A  
**Effort:** N/A  

~~Instead of serializing to `byte[]` then writing, stream directly to `AsynchronousFileChannel`.~~

**Why not applicable:**

The in-memory serialization is **required** for our change-detection strategy. The current flow is:

1. Serialize to `byte[]` in memory
2. Compare with existing file content (`Arrays.equals()`)
3. **Only write if content differs**

This prevents unnecessary file writes that would:
- Trigger virus scanners (significant latency on Windows)
- Trigger file watchers (IDE, git, backup tools)
- Update file modification timestamps unnecessarily
- Cause unnecessary disk I/O

Streaming directly to disk would bypass comparison and always write, defeating the purpose of our optimization.

---

### 14. Lazy Image Loading (Importer)
**Status:** Not Started  
**Impact:** Medium - Speed up initial import  
**Effort:** Low  

Images are loaded eagerly but may not be immediately needed. Consider lazy loading on first access.

---

### 15. Skip Unchanged Folders During Cleanup (Exporter)
**Status:** ✅ Completed (2025-12-04)  
**Impact:** Medium - Reduce directory traversal  
**Effort:** Low  

~~`cleanupObsoleteFiles()` traverses all directories. If we track which folders had changes, we can skip unchanged subtrees.~~

**Implementation:**
- Added `expectedDirectories` set that tracks all parent directories of expected files
- In `preVisitDirectory()`, check if directory is in `expectedDirectories`
- If not, delete entire subtree with `deleteDirectoryRecursively()` and return `SKIP_SUBTREE`
- Avoids traversing obsolete folder structures file-by-file

**Benefits:**
- If a folder with 1000 files is deleted from the model, we now delete it in one operation instead of visiting each file
- Reduces set lookups from O(files in subtree) to O(1) for skipped directories

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
| 2025-12-04 | #15 Skip Unchanged Folders | ✅ Completed | Track expectedDirectories, use SKIP_SUBTREE for obsolete folders |
| 2025-12-04 | #13 Streaming Serialization | ❌ N/A | In-memory required for change detection before write |
| 2025-12-04 | #5 Limit File Handles | ✅ Already Handled | Batching + ForkJoinPool + OS-level I/O management |
| 2025-12-04 | #1 Dirty Flag Tracking | ❌ Not Feasible | EMF objects are copies, not tracked across exports |
| 2025-12-04 | #2 ThreadLocal MessageDigest | ❌ N/A | Hashing eliminated - using direct byte comparison |
| 2025-12-04 | #3 Pre-compute File Mapping | ✅ Already Done | Merged pipeline stores File in ResourceWriteTask |
| 2025-12-04 | Cleanup optimization | ✅ Completed | Changed Set<File> to Set<Path> with normalization |
| 2025-12-03 | #6 Merge Hash/Write Pipeline | ✅ Completed | Merged phases + eliminated hashing via direct byte comparison |
| 2025-12-03 | #7 Pre-size Hash Cache | ❌ N/A | No longer applicable - hash cache eliminated |
| 2025-12-03 | Document created | N/A | Initial 20 optimization opportunities identified |
