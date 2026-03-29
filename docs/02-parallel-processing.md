# 02: Parallel Processing

## Problem

A single thread can't saturate modern multi-core CPUs. But naive parallelism (shared `ConcurrentHashMap`) introduces lock contention that can be worse than single-threaded execution for hot data structures.

## Technique

The 1BRC winners use a three-layer approach:

### 1. Thread-per-core (not thread pool)

```java
int cores = Runtime.getRuntime().availableProcessors();
Thread[] threads = new Thread[cores];
Map<String, Stats>[] localMaps = new HashMap[cores];
```

Platform threads are pinned 1:1 to CPU cores. No thread pool overhead, no task scheduling.

### 2. Per-thread local state

Each thread has its own `HashMap` (or custom map). **Zero synchronization** during the processing phase. This is the single biggest win over `ConcurrentHashMap`.

### 3. Work-stealing with atomic cursor

Instead of pre-splitting the file into N equal chunks (which may be unbalanced):

```java
AtomicLong cursor = new AtomicLong(fileStart);
int SEGMENT_SIZE = 2 * 1024 * 1024; // 2MB

// Each thread:
while (true) {
    long start = cursor.getAndAdd(SEGMENT_SIZE);
    if (start >= fileEnd) break;
    processSegment(start, Math.min(start + SEGMENT_SIZE, fileEnd));
}
```

Faster threads automatically consume more segments. One atomic operation per 2MB segment — negligible overhead.

### 4. Instruction-Level Parallelism (advanced)

jerrinot processes **two lines simultaneously** within the same thread:

```java
long wordA = UNSAFE.getLong(cursorA);
long wordB = UNSAFE.getLong(cursorB);
long maskA = getDelimiterMask(wordA);
long maskB = getDelimiterMask(wordB);
// Independent operations keep the CPU pipeline full
```

This hides memory latency — while one line's data is being fetched from cache, the other line's arithmetic completes.

## Final merge

After all threads finish, merge local maps sequentially:

```java
Map<String, Stats> merged = new TreeMap<>();
for (Map<String, Stats> local : localMaps) {
    local.forEach((k, v) -> merged.merge(k, v, Stats::combine));
}
```

For 10K unique stations and 8 threads, merge touches ~80K entries — trivial compared to processing 1B rows.

## When to use

- Any CPU-bound data processing workload
- When the number of unique keys is bounded (merge cost is predictable)
- File processing, log analysis, aggregation pipelines

## References

- `CalculateAverage_thomaswue.java` lines 55-81 — work-stealing pattern
- `CalculateAverage_jerrinot.java` lines 442-530 — dual-line ILP
- Example: `T02_ParallelProcessing.java`
