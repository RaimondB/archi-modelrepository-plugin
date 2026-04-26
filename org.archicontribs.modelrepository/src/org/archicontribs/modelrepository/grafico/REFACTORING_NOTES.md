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

---

## Merge Move Detection: Two-Phase Architecture

### Design

Folder moves during merge are handled in two phases:

1. **MergeConflictHandler** (conflict time): Only runs for CONFLICTING merges. Detects move groups
   via `detectFolderMoves()` 3-pass algorithm + `consolidateMoveGroups()`.
2. **GraficoModelLoader.repairMissingFolderXml()** (repair time): Runs for ALL merges (clean + conflicting).
   Detects orphaned elements, duplicate folder IDs, restores missing folder.xml.

### Dead Code Removal: cleanupAutoMergedDuplicates

`cleanupAutoMergedDuplicates()` in MergeConflictHandler was dead code — it processed 0 elements across
all test scenarios. This is because git auto-merges folder moves (both sides copy to new path), so
all moves route through the MoveGroup/consolidateMoveGroups() path instead.

### Duplicate Folder ID Detection (B6 Scenario)

When both branches move the same folder to different locations, git merges cleanly → duplicate folder IDs.
`detectDuplicateFolderIds()` in GraficoModelLoader scans the folder ID map for IDs that appear
in multiple directories and creates `FolderMoveInfo` entries for resolution.

**Data corruption chain if undetected**: Both folders export to the same directory (getNameFor returns
folder ID) → second folder.xml overwrites first → folder A disappears → elements silently reassigned.

### KEEP_NEW_LOCATION Behavior

When user chooses KEEP_NEW_LOCATION for a folder move:
- **Duplicate elements** at old location are removed (same file exists at destination)
- **Unique elements** at old location are **moved** to the new location (not kept with [MERGE FIX])
- Empty old directory is cleaned up (deleted)
- If old directory has subdirectories, it gets [MERGE FIX] folder.xml

This behavior avoids creating orphan [MERGE FIX] folders and ensures all elements follow the move.

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

## ArchiRepository as Single Gateway for All Git Operations

### The Problem

Before refactoring, native git calls were scattered across multiple classes:
- `SwitchBranchAction` had its own `tryNativeGitCheckout()`
- `RefreshModelAction` called native merge directly
- `MergeConflictHandler` called native `git add` for path staging
- Each caller duplicated the try-native/fallback-JGit pattern and state sync

This created multiple problems:
1. Every new native git optimization required changes in multiple files
2. JGit state synchronization was easy to forget (stale UI after native ops)
3. Testing required mocking native git in each caller

### Solution

**All git operations are encapsulated in `ArchiRepository`.** Callers use high-level
methods and never know whether native git or JGit is used underneath.

```java
// ✅ CORRECT: Caller doesn't know about native git
MergeResult result = ((ArchiRepository) getRepository()).merge(remoteBranch);

// ❌ WRONG: Caller manages native/JGit switching
Boolean nativeResult = tryNativeGitMerge(remoteBranch);
if (Boolean.TRUE.equals(nativeResult)) { ... }
else if (Boolean.FALSE.equals(nativeResult)) { abortNativeMerge(); }
// fall through to JGit...
```

### Key Methods

| Public Method | Internal Native Method | Fallback |
|--------------|----------------------|----------|
| `merge(remoteBranch)` | `tryNativeGitMerge` | JGit `MergeCommand` |
| `checkoutBranch(name)` | `tryNativeGitCheckout` | JGit `CheckoutCommand` |
| `gitAddPaths(paths)` | `tryNativeGitAddPaths` | JGit `AddCommand` + `RmCommand` |
| `resetToRef(ref, type)` | `tryNativeGitReset` | JGit `ResetCommand` |
| `commitChanges(...)` | `tryNativeGitAdd` (internal) | JGit `AddCommand` |
| `cloneModel(...)` | `tryNativeGitClone` (SSH only) | JGit `CloneCommand` |
| `collectDeletedElementIds(...)` | `collectDeletedIdsNative` | `collectDeletedIdsJGit` |

### Where This Applies

- `RefreshModelAction.pull()` — calls `archiRepo.merge(remoteBranch)`
- `MergeConflictHandler.stageMoveGroupPaths()` — calls `archiRepo.gitAddPaths(paths)`
- `MergeConflictHandler.resolveMoveResolvedElements()` — calls `archiRepo.gitAddPaths(paths)`
- `MergeConflictHandler.detectAndRemoveCrossPathDeletions()` — calls `ArchiRepository.collectDeletedElementIds()`
- `SwitchBranchAction` — calls `archiRepo.checkoutBranch(name)`
- Any future code that needs git operations

### Why This Matters

If we later optimize `git fetch`, `git push`, or any other operation with native git,
**every caller benefits automatically** — no changes needed outside ArchiRepository.

---

## useNativeGit Preference: System Property Precedence

### The Problem

The `useNativeGit` preference was only read from the Eclipse preference store. When
set as a system property in `Archi.ini` (`-Dorg.archicontribs.modelrepository/useNativeGit=false`),
it was ignored because the preference store always returned the default `true`.

### Solution

`isNativeGitEnabled()` checks the system property first, then falls back to the
preference store:

```java
public static boolean isNativeGitEnabled() {
    String sysProp = System.getProperty(
        "org.archicontribs.modelrepository/" + IPreferenceConstants.PREFS_USE_NATIVE_GIT);
    if (sysProp != null) {
        return Boolean.parseBoolean(sysProp);
    }
    return ModelRepositoryPlugin.getInstance().getPreferenceStore()
            .getBoolean(IPreferenceConstants.PREFS_USE_NATIVE_GIT);
}
```

This allows quick toggling for performance comparison without modifying saved preferences.

### Where This Applies

- `ArchiRepository.isNativeGitEnabled()` — the single check point
- Archi.ini — add `-Dorg.archicontribs.modelrepository/useNativeGit=false` to disable

---

## Performance Test Separation

### The Problem

`RemoteIntegrationTests` (6 tests) exercise full git clone/fetch/merge workflows.
They are I/O-heavy and slow. Running them on every build wastes developer time.

### Solution

JUnit 5 `@Tag("performance")` on `RemoteIntegrationTests`, excluded by default
in `AllTests` via `@ExcludeTags("performance")`.

```bash
# Fast build (80 tests, excludes integration):
mvn verify

# Full build (86 tests, includes integration):
mvn verify -Dinclude.perf.tests=true
```

Implementation:
- `RemoteIntegrationTests` — `@Tag("performance")` at class level
- `AllTests` — `@ExcludeTags("performance")` (default suite)
- `AllTestsWithPerformance` — no tag exclusion (full suite)
- `tests/pom.xml` — `perf-tests` Maven profile switches to full suite

### Where This Applies

- `AllTests.java` — default suite entry point
- `AllTestsWithPerformance.java` — full suite entry point
- `org.archicontribs.modelrepository.tests/pom.xml` — profile configuration
- Future slow/integration tests should use `@Tag("performance")`

---

## UI Responsiveness: Moving Git I/O Off the UI Thread

### The Problem

Eclipse SWT is single-threaded — all UI updates must run on the display thread. Git
operations (`getBranchStatus()`, `getCommits()`, `isHeadAndRemoteSame()`) can take
100ms–500ms each. If called directly from selection handlers or event listeners, the
UI freezes visibly.

Symptoms observed before the fix:
- Clicking a repository in the tree caused a 200–500ms freeze
- Branch list flickered or showed stale data
- Action buttons (Undo, Reset) were slow to update or showed wrong state
- History view lagged behind branch switches

### Solution: Background Thread + asyncExec Pattern

Every view follows the same pattern:

```java
// ❌ WRONG: Git I/O on UI thread → UI freezes
@Override
public void selectionChanged(IWorkbenchPart part, ISelection selection) {
    BranchStatus status = repo.getBranchStatus();  // 200ms+ on UI thread!
    viewer.setInput(status);
}

// ✅ CORRECT: Git I/O on background thread, UI update via asyncExec
@Override
public void selectionChanged(IWorkbenchPart part, ISelection selection) {
    Thread.ofVirtual().name("View-Load").start(() -> {
        BranchStatus status = repo.getBranchStatus();  // Background thread
        display.asyncExec(() -> {
            if (!control.isDisposed() && repo.equals(fSelectedRepository)) {
                viewer.setInput(status);  // UI thread
            }
        });
    });
}
```

### Where This Pattern Is Applied

| View / Component | Background Work | UI Callback |
|-----------------|----------------|-------------|
| `ModelRepositoryTreeViewer` | `updateStatusCache()` — parallel status for all repos | `refresh()` |
| `BranchesView.selectionChanged` | `getBranchStatus()` | `doSetInput(repo, branchStatus)` |
| `BranchesView.repositoryChanged` | `getBranchStatus()` | `doSetInput(repo, branchStatus)` |
| `HistoryTableViewer` | `getCommits()` — RevWalk up to 500 commits | `setInput(commits)` |
| `HistoryView.recomputeActionStates` | `resolve(HEAD)`, `isHeadAndRemoteSame()` | `updateActions()` |

---

## Stale Background Result Prevention

### The Problem

When a user clicks rapidly between repositories, multiple background threads can be
in flight simultaneously. If an older thread delivers its result after a newer one,
the UI shows stale data for the wrong repository.

### Solution: Thread Reference Guard

Track the current background thread. When the asyncExec callback fires, check whether
the thread is still the current one:

```java
// In HistoryTableViewer:
private final AtomicReference<Thread> fCurrentLoadThread = new AtomicReference<>();

private void loadCommitsInBackground(IArchiRepository archiRepo) {
    Thread loadThread = Thread.ofVirtual().start(() -> {
        Thread thisThread = Thread.currentThread();
        List<RevCommit> commits = getCommits(archiRepo);
        
        display.asyncExec(() -> {
            // Discard if a newer load was started
            if (fCurrentLoadThread.get() != thisThread) {
                return;  // Stale — discard
            }
            setInput(commits);
        });
    });
    fCurrentLoadThread.set(loadThread);  // Replaces reference to old thread
}
```

For views that also want to cancel the old thread (stop wasted work):

```java
// In BranchesView:
Thread oldThread = fCurrentLoadThread;
if (oldThread != null) {
    oldThread.interrupt();  // Cancel stale background work
}
fCurrentLoadThread = newThread;
```

### Where This Applies

- `HistoryTableViewer.loadCommitsInBackground()` — `AtomicReference<Thread>` guard
- `BranchesView.selectionChanged()` / `repositoryChanged()` — interrupt + replace
- `ModelRepositoryTreeViewer.refreshStatusCacheInBackground()` — interrupt + replace
- `HistoryView` — interrupt stale thread on `selectionChanged`, `HISTORY_CHANGED`, `BRANCHES_CHANGED`

---

## BranchStatus TTL Cache

### The Problem

`getBranchStatus()` creates a `BranchStatus` object which does a full RevWalk to compute
branch info and merge status. Multiple views call this within milliseconds of each other
(tree viewer, branches view, history view all respond to the same event). Without caching,
the same expensive computation runs 3–4 times.

### Solution

`ArchiRepository` caches the result for 2 seconds:

```java
private volatile BranchStatus fCachedBranchStatus;
private volatile long fBranchStatusTimestamp;
private static final long BRANCH_STATUS_TTL_NANOS = 2_000_000_000L; // 2 seconds

public BranchStatus getBranchStatus() throws IOException, GitAPIException {
    long now = System.nanoTime();
    BranchStatus cached = fCachedBranchStatus;
    if (cached != null && (now - fBranchStatusTimestamp) < BRANCH_STATUS_TTL_NANOS) {
        return cached;
    }
    BranchStatus fresh = new BranchStatus(this);
    fCachedBranchStatus = fresh;
    fBranchStatusTimestamp = System.nanoTime();
    return fresh;
}
```

**Cache invalidation**: `invalidateBranchStatusCache()` is called after every mutation
(commit, push, pull, merge, checkout, reset). This forces the next call to recompute.

### Why 2 Seconds?

- Multiple views respond to the same event within ~50ms of each other
- 2s is long enough to serve all views from one computation
- Short enough that manual user actions always get fresh data

---

## O(N²) → O(N) Branch Merge Status

### The Problem

The old `BranchStatus` computed "is branch merged?" by doing a separate RevWalk for
each branch against each other branch. With N branches, this is O(N²) RevWalks.
For repositories with 20+ branches, this caused multi-second delays.

### Solution

`computeMergedStatus()` uses `RevWalkUtils.findBranchesReachableFrom()` in a single
pass per branch (O(N) total):

```java
// Single RevWalk, reused across all branches
try (RevWalk revWalk = new RevWalk(repository)) {
    for (BranchInfo info : infos.values()) {
        List<Ref> otherRefs = allRefs.stream()
            .filter(r -> !r.getTarget().getName().equals(info.getFullName()))
            .collect(toList());
        
        List<Ref> reachable = RevWalkUtils.findBranchesReachableFrom(
            revWalk.parseCommit(branchHead), revWalk, otherRefs);
        info.setMerged(!reachable.isEmpty());
        revWalk.reset();
    }
}
```

### Where This Applies

- `BranchStatus.computeMergedStatus()` — the single O(N) implementation

---

## Action State Caching (HistoryView)

### The Problem

`UndoLastCommitAction` and `ResetToRemoteCommitAction` need git I/O to determine
their enabled state (`isHeadAndRemoteSame()`, commit count check). If `updateActions()`
is called on the UI thread (e.g., on every selection change), each action check blocks
the UI.

### Solution: Background Computation + Cached States

Action states are computed once on a background thread and cached. The UI thread
`updateActions()` reads only cached values — zero git I/O:

```java
// Cached states (set by background thread)
private volatile ObjectId fCachedHeadId;
private volatile boolean fCachedUndoEnabled;
private volatile boolean fCachedResetEnabled;

// Called from background thread after loading branch data
private void recomputeActionStates(IArchiRepository repo, BranchStatus branchStatus) {
    // ... git I/O to compute headId, undoEnabled, resetEnabled ...
    fCachedHeadId = headId;
    fCachedUndoEnabled = undoEnabled;
    fCachedResetEnabled = resetEnabled;
    
    display.asyncExec(() -> updateActions());  // Uses cached values
}

// Called on UI thread — zero git I/O
private void updateActions() {
    fActionRestoreCommit.setEnabled(commit != null && !commit.getId().equals(fCachedHeadId));
    fActionUndoLastCommit.setEnabled(fCachedUndoEnabled);
    fActionResetToRemoteCommit.setEnabled(fCachedResetEnabled);
}
```

### Quiet Repository Set

When actions are updated from a background thread, use `setRepositoryQuiet()` to
set the repository reference without triggering `shouldBeEnabled()` (which would
do git I/O on the calling thread):

```java
// ✅ CORRECT: Quiet set — no shouldBeEnabled() triggered
fActionUndoLastCommit.setRepositoryQuiet(repo);
fActionUndoLastCommit.setEnabled(cachedUndoEnabled);

// ❌ WRONG: setRepository triggers shouldBeEnabled → git I/O on UI thread
fActionUndoLastCommit.setRepository(repo);  // Calls shouldBeEnabled() internally!
```

### Where This Applies

- `HistoryView.recomputeActionStates()` — background computation
- `HistoryView.updateActions()` — UI thread consumer of cached states
- `AbstractModelAction.setRepositoryQuiet()` — base class quiet setter

---

## Event Flow: Operation → View Update

### The Complete Pipeline

When an action (e.g., Refresh, Branch Switch) completes:

```
1. Action completes operation (merge, checkout, etc.)
2. Action calls invalidateBranchStatusCache()
3. Action calls notifyChangeListeners(BRANCHES_CHANGED / HISTORY_CHANGED)
4. RepositoryListenerManager broadcasts to all registered views
5. Each view receives repositoryChanged(eventName, repository)
6. Each view spawns a background thread for git I/O
7. Background thread computes data (getBranchStatus, getCommits, etc.)
8. Background thread posts UI update via Display.asyncExec()
9. UI thread applies update (setInput, refresh, updateActions)
```

### Critical Rules

1. **Always invalidate cache before notifying listeners** — otherwise views get stale data
2. **Never call `getBranchStatus()` on the UI thread** — always in background
3. **Always guard asyncExec with disposed checks** — the view might have been closed
4. **Always check for stale results in asyncExec** — a newer load might have started
5. **Use `Display.syncExec()` only for final operations** (e.g., `saveChecksumAndNotifyListeners`)
   that must complete before the caller continues

### syncExec vs asyncExec

| Method | When to Use |
|--------|------------|
| `asyncExec` | Fire-and-forget UI updates (view refresh, label text, action states) |
| `syncExec` | Caller must wait for completion (saving state before listeners fire) |

**Prefer `asyncExec`** — it doesn't block the background thread. Use `syncExec` only
when the calling code depends on the UI operation having completed.

---

## Performance Logging: UIPerfLogger

All UI-related timing is logged via `UIPerfLogger.log(tag, message, startNanos)`:

```java
long tBg = System.nanoTime();
BranchStatus status = repo.getBranchStatus();
UIPerfLogger.log("[BranchesView]", "getBranchStatus", tBg);
// Output: [BranchesView] getBranchStatus: 153ms
```

This makes it easy to identify which operations are slow in the Archi log. The pattern
is used consistently across all views and background operations.

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
