Set up proper benchmarking for the target Java or Kotlin code, avoiding common pitfalls. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language. For Kotlin, also consider kotlinx-benchmark as an alternative to JMH.

## What to do

1. Read the target file(s)
2. Identify what needs benchmarking (methods, end-to-end, before/after comparison)
3. Set up proper benchmarking using JMH (Java/Kotlin), kotlinx-benchmark (Kotlin), or hyperfine (end-to-end)

## JMH micro-benchmark setup

### Maven dependency

```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>provided</scope>
</dependency>
```

### Benchmark template

```java
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class MyBenchmark {

    // Setup state — not measured
    @Setup(Level.Trial)
    public void setup() {
        // Initialize data, load files, etc.
    }

    @Benchmark
    public void baseline(Blackhole bh) {
        // Original implementation
        bh.consume(result); // CRITICAL: prevent Dead Code Elimination
    }

    @Benchmark
    public void optimized(Blackhole bh) {
        // Optimized implementation
        bh.consume(result);
    }
}
```

### Running JMH

```bash
mvn clean package
java -jar target/benchmarks.jar MyBenchmark -f 2 -wi 5 -i 5
```

### Kotlin JMH benchmark

JMH works directly with Kotlin — same annotations:

```kotlin
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
open class MyBenchmark {

    @Setup(Level.Trial)
    fun setup() { /* initialize data */ }

    @Benchmark
    fun baseline(bh: Blackhole) {
        bh.consume(result) // prevent DCE
    }

    @Benchmark
    fun optimized(bh: Blackhole) {
        bh.consume(result)
    }
}
```

**Kotlin note**: The benchmark class must be `open` (not `final`) for JMH to subclass it. `@State` classes also need `open`.

### kotlinx-benchmark alternative (Gradle/Kotlin-native)

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlinx.benchmark") version "0.4.12"
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.12")
}

// Benchmark class
@State(Scope.Benchmark)
@Measurement(iterations = 5, time = 1)
@Warmup(iterations = 5, time = 1)
class MyBenchmark {
    @Benchmark
    fun baseline(): Int = /* ... */
}
```

## Common pitfalls to avoid

### 1. Dead Code Elimination (DCE)

```java
// BAD: JIT removes the loop because result is unused
for (int i = 0; i < N; i++) {
    Math.sin(i);
}

// GOOD: consume the result via Blackhole
@Benchmark
public void sinBenchmark(Blackhole bh) {
    for (int i = 0; i < N; i++) {
        bh.consume(Math.sin(i));
    }
}

// ALSO GOOD: return the result (JMH auto-consumes return values)
@Benchmark
public double sinBenchmark() {
    double sum = 0;
    for (int i = 0; i < N; i++) {
        sum += Math.sin(i);
    }
    return sum;
}
```

### 2. Constant folding

```java
// BAD: JIT computes at compile time
int result = 42 * 17 + 3;

// GOOD: use @State fields (JIT can't fold them)
@State(Scope.Benchmark)
public class MyState {
    int input = 42; // opaque to JIT at compile time
}
```

### 3. No warmup

Always include warmup iterations. The first few runs include interpreter execution + JIT compilation.

### 4. Loop unrolling distortion

Use realistic data sizes. JIT may unroll tiny loops, making them appear faster than real-world usage.

## End-to-end benchmarking with hyperfine

For benchmarking the full program (startup + processing + output):

```bash
# Install
# macOS: brew install hyperfine
# Linux: cargo install hyperfine

# Basic usage
hyperfine './run.sh'

# With warmup, multiple runs, comparison
hyperfine \
    --warmup 2 \
    --runs 5 \
    'java -cp target/classes com.example.Baseline' \
    'java -cp target/classes com.example.Optimized'

# Export results
hyperfine --export-json results.json --export-markdown results.md \
    'java -cp target/classes com.example.Solution'
```

### 1BRC statistical methodology

1. Run 5 times
2. Discard fastest (may have benefited from warm caches)
3. Discard slowest (may have hit GC or OS interference)
4. Report **trimmed mean** of remaining 3

## Profiling tools

### async-profiler (CPU flamegraphs)

```bash
# Attach to running JVM
./asprof -d 30 -f flamegraph.html <pid>

# Or run with agent
java -agentpath:libasyncProfiler.so=start,event=cpu,file=profile.jfr -cp ... Main
```

### JFR (JVM Flight Recorder)

```bash
java -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
     -cp ... MainClass
```

Key events to monitor:
- `jdk.GCHeapSummary` — heap usage before/after GC
- `jdk.ObjectAllocationSample` — where allocations happen
- `jdk.CPULoad` — CPU utilization
- `jdk.ExecutionSample` — CPU profiling samples

### perf stat (hardware counters)

```bash
perf stat -e cache-misses,branch-misses,instructions,cycles \
    java -cp ... MainClass
```

Reveals whether the bottleneck is compute, memory, or control flow.

## Quick inline benchmark (no JMH)

For quick A/B comparison without JMH setup:

**Java:**
```java
static long benchmark(String label, Runnable task, int warmup, int measured) {
    for (int i = 0; i < warmup; i++) task.run();
    System.gc();
    long[] times = new long[measured];
    for (int i = 0; i < measured; i++) {
        long start = System.nanoTime();
        task.run();
        times[i] = System.nanoTime() - start;
    }
    Arrays.sort(times);
    long sum = 0;
    for (int i = 1; i < measured - 1; i++) sum += times[i];
    long trimmedMean = sum / (measured - 2);
    System.out.printf("%s: trimmed mean = %,d ns%n", label, trimmedMean);
    return trimmedMean;
}
```

**Kotlin:**
```kotlin
fun benchmark(label: String, warmup: Int, measured: Int, task: () -> Unit): Long {
    repeat(warmup) { task() }
    System.gc()
    val times = LongArray(measured) {
        val start = System.nanoTime()
        task()
        System.nanoTime() - start
    }
    times.sort()
    val trimmedMean = times.drop(1).dropLast(1).average().toLong()
    println("$label: trimmed mean = %,d ns".format(trimmedMean))
    return trimmedMean
}
```

**Kotlin trap**: Don't use `measureTimeMillis` or `measureNanoTime` for benchmarks — they have no warmup, no repetition, no statistical treatment. They're for quick wall-clock timing, not benchmarking.

## Reference

See `src/main/java/com/example/T08_Benchmarking.java` and `docs/08-benchmarking.md`.

Set up the appropriate benchmarking approach for the target code. Include proper warmup, DCE protection, and statistical treatment.
