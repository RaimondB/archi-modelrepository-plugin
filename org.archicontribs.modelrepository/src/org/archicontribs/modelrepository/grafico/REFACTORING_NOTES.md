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
