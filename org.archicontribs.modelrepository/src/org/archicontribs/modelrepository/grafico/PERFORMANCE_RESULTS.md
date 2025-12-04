# GRAFICO Export Performance Results

## Test Environment

- **Model size**: 28,240 files (~8KB average)
- **CPU**: 20 cores
- **Storage**: SSD
- **OS**: Windows
- **Java**: 21+ (virtual threads enabled)

## Current Performance (Batch-Wrapped Pipeline)

### Cold Cache (First Run)
```
Phase: Save Images:                              35ms (0 items)
Phase: Copy Model + Create ResourceSet:         459ms
Phase: Create Resources (no I/O):               902ms (28,240 items, 31,308 items/sec)
Phase: Pipeline (read+serialize+compare+write): 28,449ms (28,240 items, 993 items/sec)
Phase: Cleanup obsolete files:                  192ms
────────────────────────────────────────────────────────────────────
TOTAL EXPORT:                                   30,115ms (938 items/sec)
```

### Warm Cache (Second Run)
```
Phase: Save Images:                              23ms (0 items)
Phase: Copy Model + Create ResourceSet:         248ms
Phase: Create Resources (no I/O):               232ms (28,218 items, 121,629 items/sec)
Phase: Pipeline (read+serialize+compare+write): 762ms (28,218 items, 37,031 items/sec)
Phase: Cleanup obsolete files:                  116ms
────────────────────────────────────────────────────────────────────
TOTAL EXPORT:                                   1,411ms (19,999 items/sec)
```

### Performance Ratio
- **Cold/Warm ratio**: 21x (30s vs 1.4s)
- **Cold cache throughput**: 938 files/sec
- **Warm cache throughput**: 20,000 files/sec

---

## Architecture: Batch-Wrapped Pipeline

```
┌─────────────────────────────────────────────────────────────────┐
│                    ~1000 Batches in Parallel                     │
├─────────────────────────────────────────────────────────────────┤
│ Batch 1          │ Batch 2          │ ... │ Batch 1000          │
│ ┌─────────────┐  │ ┌─────────────┐  │     │ ┌─────────────┐     │
│ │ File 1      │  │ │ File 30     │  │     │ │ File 29K    │     │
│ │ read→ser→wr │  │ │ read→ser→wr │  │     │ │ read→ser→wr │     │
│ ├─────────────┤  │ ├─────────────┤  │     │ ├─────────────┤     │
│ │ File 2      │  │ │ File 31     │  │     │ │ File 29K+1  │     │
│ │ read→ser→wr │  │ │ read→ser→wr │  │     │ │ read→ser→wr │     │
│ ├─────────────┤  │ ├─────────────┤  │     │ ├─────────────┤     │
│ │ ...         │  │ │ ...         │  │     │ │ ...         │     │
│ │ File 29     │  │ │ File 58     │  │     │ │ File 28240  │     │
│ └─────────────┘  │ └─────────────┘  │     │ └─────────────┘     │
│   Sequential     │   Sequential     │     │   Sequential        │
│   within batch   │   within batch   │     │   within batch      │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼
              ForkJoinPool (80 threads = 4x CPU cores)
              Work-stealing across batches
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **~1000 batches** | Limits CompletableFuture allocations (1000 vs 30,000) |
| **~30 files per batch** | Balances parallelism with OS overhead |
| **Sequential I/O within batch** | Only 1 file handle open per batch at a time |
| **Blocking I/O within batch** | Simpler than async for sequential operations |
| **4x CPU cores parallelism** | Good for mixed CPU/IO workload |
| **Direct byte[] comparison** | Faster than SHA-256 hashing |

---

## Performance Evolution

### Before: Per-File Pipeline (Regression)
```
Cold cache:  132,091ms (214 items/sec)
Warm cache:    1,226ms (23,034 items/sec)
```

**Problem**: 30,000 CompletableFutures + 30,000 concurrent file handles

### After: Batch-Wrapped Pipeline (Current)
```
Cold cache:  30,115ms (938 items/sec)     ← 4.4x faster
Warm cache:   1,411ms (19,999 items/sec)  ← Similar (expected)
```

**Solution**: ~1000 batches, sequential I/O within each batch

---

## Bottleneck Analysis

### Cold Cache Breakdown
```
Pipeline phase: 28,449ms (94.5% of total time)
  └── Disk I/O: ~95% of pipeline time
      └── 28,240 file reads × ~1ms each = ~28 seconds
```

**Root cause**: SSD random read latency for small files
- Each file requires: exists() + length() + open() + read() + close()
- ~5 syscalls per file × 28,240 files = 141,200 syscalls
- SSD random read: ~50-100 μs per 4KB block
- Small file overhead: metadata lookup dominates

### Warm Cache Breakdown
```
Pipeline phase: 762ms (54% of total time)
  └── CPU: EMF serialization + byte[] comparison
      └── 28,218 files at 37,000 files/sec = pure CPU throughput
```

**Insight**: EMF serialization IS fast and DOES parallelize well

---

## What Helps vs What Doesn't

### ✅ What Helps Cold Cache

| Optimization | Impact | Why |
|--------------|--------|-----|
| Batch-wrapped pipeline | 4.4x faster | Reduces OS overhead from 30K concurrent ops |
| ~1000 batches | Significant | Good balance of parallelism vs overhead |
| Sequential I/O within batch | Significant | SSD can optimize its internal queue |
| Pre-create directories | Minor | Eliminates 28K redundant mkdirs() calls |

### ❌ What Doesn't Help Cold Cache

| Approach | Why It Doesn't Help |
|----------|---------------------|
| More parallelism | Disk I/O is bottleneck, not CPU |
| Async I/O (per-file) | OS overhead exceeds I/O benefit |
| Semaphore for handles | Windows handles 16K+ handles fine |
| SHA-256 hashing | Direct comparison is faster |

### ⚠️ What Would Help (But Breaks GRAFICO Format)

| Approach | Estimated Impact | Why Not Used |
|----------|------------------|--------------|
| Bundle files into archive | 10-50x | Breaks Git diffing per-file |
| Database storage | 10-100x | Breaks Git diffing per-file |
| RAM disk for repo | 10-50x | Requires user configuration |
| Memory-mapped files | 2-5x | Complex, may not help small files |

---

## How to Measure Performance

### Enable Performance Logging
Add JVM argument:
```
-Dgrafico.perf.logging=true
```

### View Logs
- **Eclipse**: Window → Show View → Error Log
- **File**: `<workspace>/.metadata/.log`

### Clear OS File Cache (Windows PowerShell as Admin)
```powershell
# Option 1: RAMMap from Sysinternals
RAMMap.exe /E

# Option 2: Built-in (less effective)
Write-VolumeCache C:
```

### Benchmark Protocol
1. Clear OS file cache
2. Run export (cold cache measurement)
3. Run export again immediately (warm cache measurement)
4. Report both measurements

---

## Configuration Parameters

```java
// Target number of parallel batches
final int TARGET_BATCHES = 1000;

// Parallelism for ForkJoinPool
int parallelism = cpuThreads * 4;  // 80 threads for 20 cores

// Buffer size for file operations
private static final int BUFFER_SIZE = 64 * 1024;  // 64KB
```

### Tuning Guidelines

| Scenario | Adjust |
|----------|--------|
| More CPU cores | TARGET_BATCHES can stay at 1000 |
| Slower disk (HDD) | Reduce TARGET_BATCHES to 100-500 |
| Very large model (100K+ files) | TARGET_BATCHES = 2000-5000 |
| Memory pressure | Reduce parallelism multiplier |

---

## Future Optimization Ideas

### Short-term (Low Effort)
- [ ] Skip unchanged files based on file modification time
- [ ] Cache file existence checks
- [ ] Use NIO Files.readAllBytes() instead of async for sequential reads

### Medium-term (Medium Effort)
- [ ] Parallel directory creation (currently sequential)
- [ ] Batch file existence checks using directory listing
- [ ] Pre-allocate ByteArrayOutputStream to file size

### Long-term (Breaking Changes)
- [ ] Optional archive mode for cold storage
- [ ] Incremental export based on model change tracking
- [ ] Memory-mapped file I/O for large files

---

## Version History

| Date | Change | Cold Cache | Warm Cache |
|------|--------|------------|------------|
| 2024-12-04 | Batch-wrapped pipeline | 30s (938/sec) | 1.4s (20K/sec) |
| 2024-12-03 | Per-file pipeline (regression) | 132s (214/sec) | 1.2s (23K/sec) |
| 2024-12-03 | TRUE batching baseline | ~40s (target) | ~1.5s |
