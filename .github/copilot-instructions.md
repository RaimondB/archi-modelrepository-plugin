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
4. **Progress Reporting**: Update UI every 1000 files, not per-file
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

### Code Pattern

```java
private static final int BATCH_SIZE = 100;

// Batch files to reduce CompletableFuture overhead
for (int i = 0; i < files.size(); i += BATCH_SIZE) {
    List<File> batch = files.subList(i, Math.min(i + BATCH_SIZE, files.size()));
    futures.add(CompletableFuture.runAsync(() -> processBatch(batch), executor));
}
```

## Eclipse Plugin Development

- This is an Eclipse RCP/OSGi plugin
- Uses EMF (Eclipse Modeling Framework) for model handling
- JGit for Git operations
- Minimum Java version: 21 (for virtual threads)

## Testing

Tests are in `org.archicontribs.modelrepository.tests`. Run with JUnit 5.
