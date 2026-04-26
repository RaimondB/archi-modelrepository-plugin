# ADR-0004: Async Batched File I/O for GRAFICO Export/Import

## Status

Accepted

## Date

2025-12-01 (approximate)

## Context

GRAFICO models can contain 30,000+ files. Sequential file I/O is too slow (minutes for large models). Naive parallelism (one `CompletableFuture` per file) causes excessive GC pressure from 30,000 object allocations.

## Decision

Use TRUE batching with async file I/O:

- **Batch size**: 100 files per `CompletableFuture` (30,000 files → 300 futures)
- **Separate executors**: `ForkJoinPool(availableProcessors())` for CPU work (XML serialization, SHA-256 hashing), virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) for I/O
- **Async pipeline**: Chain futures with `thenApplyAsync`/`thenComposeAsync` — never call `.join()` inside a loop
- **Progress reporting**: `ThrottledProgressReporter` with `LongAdder` for lock-free counting, time-based UI updates (every 250ms)
- **Memory**: SHA-256 hashes (32 bytes per file) instead of caching full file contents

See `PERFORMANCE_ARCHITECTURE.md` for full details.

## Consequences

- **Positive**: Export/import completes in ~1.8s warm cache, ~21s cold cache for 26,680 files
- **Positive**: Minimal GC pressure from batching
- **Positive**: UI remains responsive via throttled progress
- **Negative**: Complex async code is harder to debug
- **Negative**: File handle exhaustion possible without semaphore limiting (capped at 256 concurrent channels)
