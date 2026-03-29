# 08: Benchmarking

## Problem

Java benchmarking is tricky due to JIT compilation, GC pauses, and hardware effects. Naive `System.nanoTime()` measurements are unreliable and can lead to wrong conclusions.

## The 1BRC methodology

The 1BRC evaluation is a gold standard for fair, reproducible Java benchmarking.

### Hardware control

```
Machine: Hetzner AX161
CPU:     AMD EPYC 7502P (32 cores, Zen2)
RAM:     128 GB DDR4
```

Critical settings:
- **SMT disabled**: Eliminates hyperthreading interference
- **Turbo Boost disabled**: Consistent clock speed across runs
- **CPU pinning**: `numactl --physcpubind=0-7` (8 of 32 cores)
- **EXT4 on RAM disk**: Eliminates I/O variance

### Measurement protocol

```bash
# Using hyperfine (external tool, not JVM-internal)
hyperfine \
  --warmup 1 \
  --runs 5 \
  --time-limit 300 \
  './calculate_average_solution.sh'
```

Statistical treatment:
1. Run 5 times
2. Discard fastest (may have benefited from warm caches)
3. Discard slowest (may have hit GC or OS interference)
4. Report **trimmed mean** of remaining 3

### Why external measurement

JMH handles micro-benchmarks. But for the 1BRC, **end-to-end time matters** because:
- JVM startup time varies between solutions (esp. GraalVM native vs HotSpot)
- Memory mapping setup cost is real
- GC during shutdown affects total time

## Common pitfalls

### 1. Dead Code Elimination (DCE)

```java
// BAD: JIT removes this because result is unused
for (int i = 0; i < N; i++) {
    Math.sin(i);
}

// GOOD: consume the result
long sum = 0;
for (int i = 0; i < N; i++) {
    sum += Double.doubleToLongBits(Math.sin(i));
}
return sum; // force computation
```

### 2. No warmup

The first run includes interpreter execution + JIT compilation. Always:
- Discard at least 2-3 warmup iterations
- Or use JMH's `@Warmup(iterations = 5)` annotation

### 3. Constant folding

```java
// BAD: JIT computes this at compile time
int result = 42 * 17 + 3;

// GOOD: use a non-constant input
int result = input * 17 + 3;  // 'input' from parameter/field
```

### 4. Loop unrolling distortion

JIT may unroll small loops, making them appear faster than they would be with real data patterns. Use realistic data sizes and patterns.

## Tools

| Tool | Use case |
|------|----------|
| **JMH** | Micro-benchmarks (method-level) |
| **hyperfine** | End-to-end CLI benchmarks |
| **async-profiler** | CPU profiling (flamegraphs) |
| **JFR** | JVM-level profiling (GC, allocation, locks) |
| **perf stat** | Hardware counters (cache misses, branch mispredictions) |

### Key JFR events for data-intensive code

- `jdk.GCHeapSummary` — heap usage before/after GC
- `jdk.ObjectAllocationSample` — where allocations happen
- `jdk.CPULoad` — CPU utilization per thread
- `jdk.ExecutionSample` — CPU profiling samples

### Key perf counters

```bash
perf stat -e cache-misses,branch-misses,instructions java -cp ... MainClass
```

These reveal whether your bottleneck is compute (instructions), memory (cache-misses), or control flow (branch-misses).

## References

- `evaluate.sh` — 1BRC evaluation script with hyperfine + numactl
- `ENVIRONMENT.md` — detailed hardware/OS configuration
- Example: `T08_Benchmarking.java`
