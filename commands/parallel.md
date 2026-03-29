Apply thread-per-core parallel processing with work-stealing to the target code. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## What to do

1. Read the target file(s)
2. Identify the main processing loop and shared state
3. Apply the 1BRC parallelization pattern: thread-per-core + per-thread local state + work-stealing + final merge

## The technique

### Pattern 1: Thread-per-core with per-thread state (standard)

```java
int cores = Runtime.getRuntime().availableProcessors();
Thread[] threads = new Thread[cores];
@SuppressWarnings("unchecked")
Map<String, Stats>[] localMaps = new HashMap[cores];

for (int t = 0; t < cores; t++) {
    final int threadIdx = t;
    localMaps[threadIdx] = new HashMap<>();
    threads[t] = new Thread(() -> {
        Map<String, Stats> local = localMaps[threadIdx];
        // Process assigned chunk using local map — ZERO synchronization
        processChunk(local, startOffset, endOffset);
    });
    threads[t].start();
}
for (Thread t : threads) t.join();

// Merge (trivial for bounded key cardinality)
Map<String, Stats> merged = new TreeMap<>();
for (var local : localMaps) {
    local.forEach((k, v) -> merged.merge(k, v, Stats::combine));
}
```

### Pattern 2: Work-stealing with atomic cursor (advanced)

Instead of pre-splitting data equally (which may be unbalanced):

```java
AtomicLong cursor = new AtomicLong(0);
long fileSize = data.byteSize();
int SEGMENT_SIZE = 2 * 1024 * 1024; // 2MB segments

// Each thread:
Runnable worker = () -> {
    Map<String, Stats> local = new HashMap<>();
    while (true) {
        long start = cursor.getAndAdd(SEGMENT_SIZE);
        if (start >= fileSize) break;
        long end = Math.min(start + SEGMENT_SIZE, fileSize);
        // Align to line boundary
        if (start > 0) start = findNextNewline(data, start) + 1;
        if (end < fileSize) end = findNextNewline(data, end) + 1;
        processSegment(data, start, end, local);
    }
    // Store local map for merge
};
```

Key advantage: faster threads automatically consume more segments. One atomic op per 2MB — negligible overhead.

### Pattern 3: Instruction-Level Parallelism (expert)

Process two lines simultaneously within the same thread to hide memory latency:

```java
long wordA = data.get(ValueLayout.JAVA_LONG_UNALIGNED, cursorA);
long wordB = data.get(ValueLayout.JAVA_LONG_UNALIGNED, cursorB);
long maskA = findDelimiter(wordA);
long maskB = findDelimiter(wordB);
// Independent operations keep the CPU pipeline full
```

### Kotlin: thread-per-core with per-thread state

```kotlin
val cores = Runtime.getRuntime().availableProcessors()
val localMaps = Array(cores) { HashMap<String, Stats>() }

val threads = Array(cores) { t ->
    thread {
        val local = localMaps[t]
        // Process assigned chunk — ZERO synchronization
        processChunk(local, startOffset, endOffset)
    }
}
threads.forEach { it.join() }

// Merge
val merged = TreeMap<String, Stats>()
for (local in localMaps) {
    local.forEach { (k, v) -> merged.merge(k, v, Stats::combine) }
}
```

### Kotlin: work-stealing with atomic cursor

```kotlin
val cursor = AtomicLong(0)
val segmentSize = 2 * 1024 * 1024L

val threads = Array(cores) {
    thread {
        val local = HashMap<String, Stats>()
        while (true) {
            val start = cursor.getAndAdd(segmentSize)
            if (start >= fileSize) break
            val end = minOf(start + segmentSize, fileSize)
            processSegment(data, start, end, local)
        }
    }
}
```

**Kotlin note**: Do NOT use `coroutineScope`/`Dispatchers.Default` for this pattern. Coroutines add scheduling overhead and make per-thread-pinned state harder. Use `kotlin.concurrent.thread {}` for direct platform threads. Coroutines are great for I/O-bound work, but for CPU-bound data processing with per-thread state, raw threads win.

## Critical rules

- **Never** use `ConcurrentHashMap` (or Kotlin `ConcurrentHashMap`) in the hot loop — lock contention kills performance
- Each thread must have its own local state (HashMap, array, whatever)
- Merge happens **once** after all threads finish — trivial cost for bounded key sets
- Always align chunk boundaries to line boundaries (don't split mid-record)
- **Kotlin-specific**: Avoid `synchronized {}`, `Mutex`, or `Channel` in the hot path

## When NOT to apply

- I/O-bound workloads (parallelism won't help if disk is the bottleneck)
- Unbounded key cardinality (merge cost becomes significant)
- Code that's already fast enough single-threaded

## Reference

See `src/main/java/com/example/T02_ParallelProcessing.java` and `docs/02-parallel-processing.md`.

Apply the transformation, choosing the appropriate pattern based on the code structure. Show the complete modified code.
