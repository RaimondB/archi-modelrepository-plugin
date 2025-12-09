# Grafico Refactoring Notes

This document captures important design decisions and lessons learned during refactoring of the Grafico I/O code. **Keep this document updated** when making changes to avoid repeating past mistakes.

## Change Detection: Export vs Git Status

### The Problem

When checking if a model has changes before performing git operations (add, commit), there are two approaches:

1. **Git Status Check** (`hasChangesToCommit()`) - Checks `git status` for uncommitted changes
2. **Export Result Check** - Use the return value from `exportModel()` which tracks written/deleted files

### Why Export Result is Correct

```
Timeline of operations:
1. exportModel() writes/deletes files → tracks changes internally
2. git add (stages changes)
3. git status (shows staged + unstaged changes)
4. git commit
```

**Critical insight**: `hasChangesToCommit()` checks git status, which only reliably shows changes AFTER `git add` has staged them. If you call `hasChangesToCommit()` BEFORE `git add`, it may:
- Miss newly written files (untracked files)
- Miss deletions (until staged)
- Show stale state from previous operations

### Correct Pattern

```java
// ✅ CORRECT: Use export's return value
GraficoModelExporter exporter = new GraficoModelExporter(model, folder);
boolean hasChanges = exporter.exportModel(monitor);

if (hasChanges && doGitAdd) {
    // Only run git add if there are actual changes
    git.add().addFilepattern(".").call();
}

// ❌ WRONG: Check git status before staging
exporter.exportModel(monitor);
boolean hasChanges = hasChangesToCommit(); // Wrong! Changes not staged yet!
```

### Implementation Details

The `GraficoModelExporter` tracks:
- `writtenFiles`: Set of files that were actually written (content changed)
- `deletedFilesCount`: Counter of files deleted during cleanup

The `exportModel()` method returns `true` if:
```java
return writtenFiles.size() > 0 || deletedFilesCount.get() > 0;
```

### Where This Applies

- `ArchiRepository.exportModelToGraficoFiles(IProgressMonitor)` - Uses export result, always does git add
- `SwitchBranchAction.run()` - Uses export result to detect changes
- Any future code that needs to know if export made changes

---

## Progress Reporting: Throttling and Thread Safety

### The Problem

With 30,000 files, calling `monitor.subTask()` or `monitor.worked()` per file causes:
- UI thread contention (Eclipse progress dialog syncs with UI)
- Cache line bouncing with `AtomicInteger` counters
- Visible lag in the progress dialog

### Solution: ThrottledProgressReporter

Use `ThrottledProgressReporter` for time-based UI updates:

```java
// Updates at most every 250ms, not per-file
ThrottledProgressReporter reporter = new ThrottledProgressReporter(monitor, totalFiles);

// In async callbacks (many threads):
reporter.incrementBy(1);
reporter.maybeReport(count -> String.format("Processing %d of %d", count, total));

// At end:
reporter.finish(null);
```

### Key Benefits

- Uses `LongAdder` instead of `AtomicInteger` (no cache-line bouncing)
- Time-based throttling (250ms) provides smooth UI updates
- Uses `Display.asyncExec()` for all UI updates - **safe to call from ANY thread**
- Only one thread updates UI at a time (via scheduled executor)

---

## UI Thread Constraints: Model Loading

### The Problem

`GraficoModelLoader.loadModel()` performs operations that trigger UI updates:
- `IEditorModelManager.INSTANCE.saveModel()` - fires property changes → `SaveAction.setEnabled()` → `Shell.setModified()`
- `IEditorModelManager.INSTANCE.closeModel()` / `openModel()` - modifies Eclipse workbench
- `reopenEditors()` - opens diagram editors

If these operations run in a background thread (e.g., inside `busyCursorWhile`), you get:
```
org.eclipse.swt.SWTException: Invalid thread access
```

### Solution: Separate Import and UI Phases

The `GraficoModelImporter.importAsModel()` method is pure file I/O and can run in a background thread with `ThrottledProgressReporter`.

The post-import operations in `GraficoModelLoader` **MUST run on the UI thread**.

```java
// ✅ CORRECT: Git checkout in background, model loading on UI thread
private void switchBranchWithProgress(BranchInfo branchInfo, boolean doReload) throws ... {
    // Phase 1: Git checkout in background thread
    PlatformUI.getWorkbench().getProgressService().busyCursorWhile(pm -> {
        performGitCheckoutWithMonitor(branchInfo, progress);
    });
    
    // Phase 2: Model loading on UI thread (we're back from busyCursorWhile)
    if(doReload) {
        new GraficoModelLoader(getRepository()).loadModel();  // Creates own progress dialog
    }
}
```

### Combined Import Pattern (Single Progress Dialog)

For a truly unified progress experience, use `GraficoModelImporter` directly in the background,
then call `GraficoModelLoader.openModel()` on the UI thread:

```java
// ✅ BEST: Single progress dialog for everything
IArchimateModel[] importedModel = new IArchimateModel[1];
GraficoModelImporter[] importerRef = new GraficoModelImporter[1];

PlatformUI.getWorkbench().getProgressService().busyCursorWhile(pm -> {
    SubMonitor progress = SubMonitor.convert(pm, "Switching branch...", 100);
    
    // Phase 1: Git checkout (30%)
    performGitCheckout(branchInfo, progress.split(30));
    
    // Phase 2: Import model files (70%) - pure I/O, safe in background
    importerRef[0] = new GraficoModelImporter(repository.getLocalRepositoryFolder());
    importedModel[0] = importerRef[0].importAsModel(progress.split(70));
});

// Phase 3: UI operations on UI thread (we're back from busyCursorWhile)
if(importedModel[0] != null) {
    new GraficoModelLoader(repository).openModel(importedModel[0], importerRef[0]);
}
```

### Where This Applies

- `SwitchBranchAction.switchBranchWithProgress()` - uses combined import pattern
- `MergeBranchAction` - if combining operations, same pattern applies
- Any future code that combines background work with model operations

---

## Async I/O: Batching and Proper Chaining

### The Problem

Creating 30,000 `CompletableFuture` objects causes GC pressure (~6MB allocations).

### Solution: Batch-Wrapped Pipeline

```java
// ONE future per batch, processes files sequentially within batch
final int batchSize = Math.max(1, (files.size() + targetBatches - 1) / targetBatches);

for (int i = 0; i < files.size(); i += batchSize) {
    final List<File> batch = files.subList(i, Math.min(i + batchSize, files.size()));
    
    CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
        for (File file : batch) {
            // Sequential I/O within batch (one file handle at a time)
            processFile(file);
        }
    }, cpuExecutor);
    
    futures.add(batchFuture);
}
```

### OS-Specific Batch Limits

```java
private int calculateOptimalBatchCount(int totalFiles, int cpuThreads) {
    String os = System.getProperty("os.name").toLowerCase();
    int maxBatches;
    if (os.contains("mac")) {
        maxBatches = 150;  // macOS has 256 soft file handle limit
    } else if (os.contains("linux")) {
        maxBatches = 500;  // Linux typically 1024+ limit
    } else {
        maxBatches = 1000; // Windows has high limit
    }
    return Math.min(maxBatches, ...);
}
```

---

## Cancellation Handling in Branch Switch

### The Problem

When switching branches, we have multiple phases that can be cancelled:
1. **Export phase**: Writing model to disk + git add
2. **User decision**: Commit dialog, proceed dialog
3. **Checkout phase**: Git checkout
4. **Import phase**: Reading model files

Each cancellation point has different stable state requirements.

### Stable States

| Cancellation Point | Stable State | Recovery Action |
|-------------------|--------------|-----------------|
| During export | Current branch, original files | `resetToRef(HEAD)` to discard partial export |
| User cancels commit dialog | Current branch, staged changes | `resetToRef(HEAD)` to unstage and discard |
| User cancels "proceed without commit" | Current branch, staged changes | `resetToRef(HEAD)` to unstage and discard |
| Before git checkout | Current branch | None needed (nothing changed) |
| During git checkout | Git handles atomically | Git will fail safely |
| During import | NEW branch in git | **Must complete import** - don't cancel |

### Critical Insight: Import Cannot Be Cancelled

Once git checkout completes, the working directory is on the new branch. If we cancel the import:
- Git state: new branch
- Model in memory: old branch's model
- UI: showing stale model

This is an **inconsistent state** that will cause problems on next commit or refresh.

**Solution**: After checkout completes, we must complete the import even if user clicked cancel.

```java
// Phase 1: Git checkout - cancellable
if(progress.isCanceled()) {
    throw new InterruptedException("Cancelled before checkout");
}
performGitCheckout(...);
checkoutCompleted = true;

// Phase 2: Import - NOT cancellable (must complete after checkout)
if(progress.isCanceled()) {
    wasCancelled = true;  // Note it, but continue!
}
importModel(...);  // Always complete this
```

### Where This Applies

- `SwitchBranchAction.run()` - handles cancellation during export and user dialogs
- `SwitchBranchAction.switchBranchWithProgress()` - handles cancellation during checkout/import
- `MergeBranchAction` - similar pattern for merge operations

---

## DirCache Optimization for Export

### The Problem

When exporting 30,000 model files, we need to compare new content with existing files to avoid unnecessary writes. The naive approach reads each file from disk:

```java
// ❌ SLOW: Reads 30,000 files from disk (240MB+ of I/O)
byte[] existingContent = Files.readAllBytes(file.toPath());
if (!Arrays.equals(existingContent, newContent)) {
    Files.write(file.toPath(), newContent);
}
```

With a cold disk cache, this causes significant latency as each file read blocks.

### Solution: Use Git's DirCache (Index)

When exporting to a git repository, we can use the DirCache (the `.git/index` file) which already contains SHA-1/SHA-256 hashes of all tracked files:

```java
// ✅ FAST: No disk read needed - just compute hash of new content
ObjectId existingHash = getHashFromDirCache(file);  // From .git/index
ObjectId newHash = computeGitBlobHash(newContent);   // CPU-only
if (!existingHash.equals(newHash)) {
    Files.write(file.toPath(), newContent);
}
```

**Performance benefit:**
- Hash computation (~10μs) vs file read on cold cache (~1ms) = 100x faster per file
- Avoids OS file descriptor overhead
- Leverages git's existing index data structure

### Hash Algorithm Compatibility

Git repositories may use SHA-1 (legacy) or SHA-256 (modern, configured in `.gitconfig`):

```java
// ✅ CORRECT: Use ObjectInserter which auto-detects repository's hash algorithm
ObjectInserter inserter = repository.newObjectInserter();
ObjectId hash = inserter.idFor(Constants.OBJ_BLOB, content);

// ❌ WRONG: Don't hardcode MessageDigest algorithm!
MessageDigest md = MessageDigest.getInstance("SHA-1");  // May not match repo
```

### Fallback for Non-Git Folders

The exporter can also be used on regular folders (not git repositories). In this case, we fall back to reading file contents:

```java
private boolean hasContentChanged(File file, byte[] newContent) {
    // If DirCache is available, use hash comparison
    if (fDirCache != null && fObjectInserter != null) {
        ObjectId existingHash = getHashFromDirCache(file);
        if (existingHash != null) {
            ObjectId newHash = computeGitBlobHash(newContent);
            return !existingHash.equals(newHash);
        }
        return true; // File not in index (new file)
    }
    
    // Fall back to reading file and comparing bytes
    if (!file.exists()) return true;
    return !Arrays.equals(readFileBytes(file), newContent);
}
```

### Resource Lifecycle

DirCache resources must be properly managed:

```java
// In exportModel():
try {
    boolean useDirCache = initDirCache();  // Opens Repository, reads index
    // ... export logic ...
} finally {
    cleanupDirCache();  // MUST close ObjectInserter and Repository
}
```

### Where This Applies

- `GraficoModelExporter.exportModel()` - uses `hasContentChanged()` method
- `GraficoModelExporter.initDirCache()` - initializes DirCache if git repo
- `GraficoModelExporter.hasContentChanged()` - handles both git and non-git cases

---

## DirCache Optimization for Import

### The Problem

After the export optimization (which uses DirCache for hash comparison instead of reading files), the disk cache is cold when import runs. The original import implementation had two bottlenecks:

1. **Sequential folder traversal**: Each folder's `folder.xml` was loaded before processing children
2. **Per-folder file discovery**: Used `Files.list()` for each folder (repeated disk I/O)

```java
// ❌ SLOW: Folder-by-folder discovery and loading
for (FolderType folderType : folderList) {
    IFolder folder = loadFolder(new File(modelFolder, folderType.toString()));
    // loadFolder() calls Files.list() for each subfolder (disk I/O)
    // loadFolder() recursively processes children (sequential)
}
```

### Solution: DirCache-Based Bulk Parallel Loading

Use DirCache to discover ALL files upfront, then read them in parallel:

```java
// ✅ FAST: Use DirCache for file discovery (no disk I/O)
List<DirCacheFileEntry> allEntries = collectFilesFromDirCache();

// Separate folder.xml files (structure) from element files (content)
List<DirCacheFileEntry> folderXmlFiles = allEntries.stream()
    .filter(e -> e.isFolderXml())
    .sorted((a, b) -> a.getDepth() - b.getDepth())  // Parents first
    .collect(toList());

List<DirCacheFileEntry> elementFiles = allEntries.stream()
    .filter(e -> !e.isFolderXml())
    .collect(toList());

// Phase 1: Read ALL folder.xml files in parallel, then build hierarchy
CountDownLatch folderLatch = new CountDownLatch(folderXmlFiles.size());
for (DirCacheFileEntry entry : folderXmlFiles) {
    readAndParseDirect(entry.absolutePath(), folderContents, folderLatch);
}
folderLatch.await();

// Build folder hierarchy (sequential, but fast - data already in memory)
for (DirCacheFileEntry entry : folderXmlFiles) {
    // Create IFolder, add to parent
}

// Phase 2 & 3 OVERLAPPED: Read elements and add to model concurrently
// Producer/Consumer pattern - no waiting for all reads to complete
BlockingQueue<ElementWithFolder> queue = new LinkedBlockingQueue<>();
AtomicInteger remaining = new AtomicInteger(elementFiles.size());

// Start ALL element reads - each puts result in queue when done
for (DirCacheFileEntry entry : elementFiles) {
    readParseAndQueueElement(entry, queue, remaining, ...);
}

// Consumer loop: add to model as elements arrive (single-threaded, EMF safe)
while (elementsProcessed < totalElements) {
    ElementWithFolder item = queue.poll(50, TimeUnit.MILLISECONDS);
    if (item != null) {
        IFolder parent = fFolderPathLookup.get(item.folderPath());
        parent.getElements().add(item.element());
        elementsProcessed++;
    }
}
```

**Performance Benefits:**
- Zero disk I/O for file discovery (DirCache is already in memory)
- ALL async file reads start simultaneously (maximum I/O parallelism)
- Folder hierarchy built from in-memory data (no per-folder blocking)
- **Overlapped I/O and model building** - elements added as soon as parsed

### Overlapped Producer/Consumer Pattern

The key optimization is overlapping disk I/O with model building. Since the folder hierarchy is pre-built, elements can be added in any order:

```
Timeline (overlapped):
Thread 1 (I/O):      [read file A][read file B][read file C]...
Thread 2 (CPU):           [parse A]    [parse B]    [parse C]...
Main Thread (EMF):            [add A]      [add B]      [add C]...
                    ←————— Maximum overlap, no waiting ——————→
```

Compare to sequential:
```
Timeline (sequential - OLD):
[read all files]...[wait]...[parse all]...[wait]...[add all to model]
                    ←————— Wasted time waiting ——————→
```

### Pre-Flight Folder Hierarchy

The key insight is that folder.xml files define the folder structure. By reading them first and sorting by depth, we can create parent folders before children:

```java
// Sort folder.xml files by depth (parents before children)
folderXmlFiles.sort((a, b) -> a.getDepth() - b.getDepth());

// Create folders in hierarchy order
for (DirCacheFileEntry entry : folderXmlFiles) {
    IFolder folder = (IFolder) folderContents.get(entry.absolutePath());
    
    // Find parent folder
    IFolder parent = fFolderPathLookup.get(parentPath);
    parent.getFolders().add(folder);
    
    // Register for children
    fFolderPathLookup.put(entry.folderPath(), folder);
}
```

### Fallback for Non-Git Folders

The importer can also be used on regular folders. In this case, it falls back to the traditional folder-by-folder loading:

```java
boolean useDirCache = initDirCacheForImport();
if (useDirCache) {
    fModel = loadModelWithDirCache(modelFolder, allEntries, totalFiles);
} else {
    fModel = loadModel(modelFolder, totalFiles);  // Traditional approach
}
```

### Where This Applies

- `GraficoModelImporter.importAsModel()` - tries DirCache first, falls back to traditional
- `GraficoModelImporter.loadModelWithDirCache()` - bulk parallel loading
- `GraficoModelImporter.collectFilesFromDirCache()` - file discovery without disk I/O

---

## Executor Choice: ForkJoinPool vs Virtual Threads for I/O

### The Problem

When processing 26,680 files with 1000 batches, choosing the wrong executor drastically limits throughput:

| Executor | Concurrent Operations | Cold Cache Result |
|----------|----------------------|-------------------|
| `ForkJoinPool(20)` | 20 (CPU cores) | 1,191 items/sec |
| Virtual Threads | 1000 (batch count) | 1,721 items/sec |

**Why ForkJoinPool failed**: It's sized for CPU-bound work (cores = 20). With 1000 batches submitted, only 20 could run concurrently. Each file read takes ~17ms on cold cache, so throughput was limited to:

```
20 threads × (1000ms / 17ms per file) ≈ 1,176 files/sec
```

This matched the observed 1,191 items/sec exactly.

### Solution: Use Virtual Threads for I/O-Bound Batches

```java
// ❌ WRONG: ForkJoinPool limits concurrent I/O to CPU core count
batchFutures.add(CompletableFuture.runAsync(() -> {
    for (int j = start; j < end; j++) {
        readParseAndQueueStreaming(elementFiles.get(j), ...);
    }
}, fCpuExecutor));  // Only 20 concurrent batches!

// ✅ CORRECT: Virtual threads allow all 1000 batches to run concurrently
batchFutures.add(CompletableFuture.runAsync(() -> {
    for (int j = start; j < end; j++) {
        readParseAndQueueStreaming(elementFiles.get(j), ...);
    }
}, fIoExecutor));  // 1000 concurrent batches, each blocking on I/O
```

### Performance Results (Cold Cache, 26,680 files)

| Approach | Time | Rate | Improvement |
|----------|------|------|-------------|
| ForkJoinPool(20) | 22.4s | 1,191/sec | baseline |
| Virtual Threads (1000 batches) | 15.5s | 1,721/sec | **+44%** |

### Key Metrics to Watch

From the performance logs:

1. **`readParse` cumulative time**: Total time spent reading/parsing across all threads
   - Virtual threads: 3,813,430ms cumulative / 1000 batches ≈ 3.8s per batch
   - Divided by wall-clock 15.5s = high parallelism achieved

2. **`queuePut` cumulative time**: 4,936,127ms indicates queue contention is now the bottleneck
   - 1000 producers fighting for `ArrayBlockingQueue(2000)` lock
   - Next optimization: consider `LinkedBlockingQueue` or larger capacity

3. **`pollSuccess` time**: 15,333ms - consumer spends most time waiting for queue
   - Consumer is NOT the bottleneck (add=130ms, lookup=34ms are fast)
   - Producers aren't filling queue fast enough (queue contention from put())

### The Pipeline Math

With virtual threads:
```
readParse cumulative = 3,813,430ms
Number of files = 26,680
Per-file average = 142,932μs ≈ 143ms (includes I/O wait + parse)

Wall clock = 15.5s
Effective parallelism = 3,813,430ms / 15,500ms ≈ 246 concurrent operations
```

This is lower than expected 1000 - the `ArrayBlockingQueue` put() contention limits actual parallelism.

### Remaining Bottleneck: Queue Contention

The `queuePut` cumulative time (4,936,127ms) exceeds even `readParse` time (3,813,430ms). This indicates:
- 1000 virtual threads competing for queue lock
- `ArrayBlockingQueue` uses a single `ReentrantLock` for all operations
- Each put() must acquire the lock, even when queue isn't full

**Future optimization**: Consider:
1. `LinkedBlockingQueue` - separate locks for head/tail
2. `ConcurrentLinkedQueue` with separate counter - lock-free
3. Multiple queues (one per N batches) - reduces contention

### Where This Applies

- `GraficoModelImporter.loadModelWithDirCache()` - Phase2 element loading
- Any future bulk file I/O operations

---

## EMF Parser Pool and Feature Map Caching

### The Problem

When parsing 26,679 XML files concurrently, two bottlenecks emerged:

1. **SAXParserFactory synchronization**: Each parse calls `SAXParserFactory.newInstance()` which is synchronized
2. **Repeated schema lookups**: EMF resolves XML element names to EStructuralFeature for each element, each file

With virtual threads (1000 concurrent batches), the synchronized SAXParserFactory became a bottleneck:
- Only ~20-30 effective parallel operations despite 1000 virtual threads
- CPU exhausted but disk NOT saturated (classic CPU-bound bottleneck)

### Solution

EMF provides built-in options for parser and handler pooling:

```java
// Thread-safe parser pool - sized for concurrent access
private static final XMLParserPool PARSER_POOL = new XMLParserPoolImpl(
    Runtime.getRuntime().availableProcessors() * 2,  // Pool size matches concurrency
    true  // Also cache XMLDefaultHandler instances
);

// Feature map caching - shared across all loads
private static final Map<Object, Object> XML_NAME_TO_FEATURE_MAP = 
    Collections.synchronizedMap(new HashMap<>());

// Add to LOAD_OPTIONS
opts.put(XMLResource.OPTION_USE_PARSER_POOL, PARSER_POOL);
opts.put(XMLResource.OPTION_USE_XML_NAME_TO_FEATURE_MAP, XML_NAME_TO_FEATURE_MAP);
opts.put(XMLResource.OPTION_USE_DEPRECATED_METHODS, Boolean.FALSE);  // Required with pool
```

**Key benefits:**
- `OPTION_USE_PARSER_POOL`: Eliminates SAXParser creation overhead (~100-1000μs per file)
- `OPTION_USE_XML_NAME_TO_FEATURE_MAP`: Caches schema lookups (significant for repeated elements)
- `OPTION_USE_DEPRECATED_METHODS = false`: Required when using parser pool, uses modern code paths

### Important Considerations

1. **Parser pool is thread-safe**: `XMLParserPoolImpl` is explicitly documented as thread-safe
2. **Pool sizing**: Size should match max concurrency (we use 2x CPU cores)
3. **Handler caching**: Set `useHandlerCache=true` for additional optimization
4. **Feature map is shared**: All loads benefit from cached schema lookups

### Where This Applies

- `GraficoResourceLoader.java` - Static LOAD_OPTIONS with pool configuration
- Any EMF XMLResource loading that needs high throughput

---

## Executor Choice: Lessons from Import Optimization (UPDATED December 2025)

### The Problem

When optimizing the import of ~26,680 files, we tried multiple concurrency approaches. Each had different tradeoffs:

| Approach | Cold Cache Time | Rate | Why |
|----------|-----------------|------|-----|
| Sequential | 95s | 279/s | Baseline - no parallelism |
| parallelStream (no batching) | 44.5s | 599/s | 2.1x speedup |
| Virtual Threads (1000 batches) | 64.5s | 413/s | **SLOWER** - thread pinning! |
| AsyncFileChannel (200 handles) | 62.8s | 425/s | Peak=22 handles only |
| **CompletableFuture + ForkJoinPool** | **20.2s** | **1,322/s** | **WINNER - 4.7x speedup** |

### Key Findings (CRITICAL)

1. **Virtual Threads are SLOWER than ForkJoinPool for cold cache file I/O!**
   - `Files.readAllBytes()` uses synchronized methods
   - Virtual threads PIN to carrier threads on synchronized code
   - Result: Only ~20 effective threads despite 1000 virtual threads

2. **Batching is CRITICAL for performance**
   - parallelStream (no batching): 44.5s
   - CompletableFuture with 1000 batches: 20.2s
   - **2x improvement from batching alone!**

3. **AsyncFileChannel doesn't help**
   - Despite "true async", semaphore overhead makes it slower
   - Peak concurrent handles: 22 (not 200 as configured)

4. **The 20s floor is disk metadata overhead**
   - 26,679 files × ~0.75ms per file = 20s
   - This is NTFS MFT lookups, not data transfer
   - Throughput: 1.9 MB/s (vs 500+ MB/s theoretical)

### Current Recommendation (CHANGED)

Use **CompletableFuture + ForkJoinPool** (NOT virtual threads):

```java
ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
int batchCount = 1000;
int batchSize = (files.size() + batchCount - 1) / batchCount;

List<CompletableFuture<Void>> futures = new ArrayList<>();
for (int i = 0; i < files.size(); i += batchSize) {
    final int start = i;
    final int end = Math.min(i + batchSize, files.size());
    
    futures.add(CompletableFuture.runAsync(() -> {
        for (int j = start; j < end; j++) {
            byte[] data = Files.readAllBytes(files.get(j).toPath());
            // Process data...
        }
    }, pool));
}

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

**Key principles:**
1. **ForkJoinPool** for I/O (not virtual threads - they pin on synchronized I/O)
2. **1000 batches** for optimal work-stealing
3. **Sequential within batch** to reduce contention
4. **Producer/consumer queue** for EMF thread safety

### Performance Comparison (Cold Cache)

| Executor | Batching | Time | Rate |
|----------|----------|------|------|
| ForkJoinPool + parallelStream | No | 44.5s | 599/s |
| Virtual Threads | 1000 batches | 64.5s | 413/s |
| **ForkJoinPool + CompletableFuture** | **1000 batches** | **20.2s** | **1,322/s** |

### Strategies to Go Below 20s

The 20s floor is caused by per-file metadata overhead. Possible solutions:

1. **Reduce file count** - Bundle elements (requires format change)
2. **Pre-warm cache during export** - Background thread reads files
3. **Windows Defender exclusion** - May reduce scanning overhead
4. **Memory-mapped files** - `MappedByteBuffer` for zero-copy
5. **Increase batch concurrency** - Test with more batches

### Where This Applies

- `GraficoModelImporter.loadModelWithDirCache()` - Phase2 element loading
- `GraficoModelImporter.readParseAndQueueStreaming()` - File reading method
- Any future bulk file I/O operations

---

## Keeping This Document Updated

**INSTRUCTION FOR AI ASSISTANTS AND DEVELOPERS:**

When refactoring Grafico I/O code, always:

1. Check this document for relevant patterns and pitfalls
2. Add new entries when discovering important design decisions
3. Update existing entries if the implementation changes
4. Include code examples that show both WRONG and CORRECT patterns

Format for new entries:
```markdown
## [Topic Name]

### The Problem
[What goes wrong if you do it the naive way]

### Solution
[The correct approach with code example]

### Where This Applies
[List of files/methods affected]
```
