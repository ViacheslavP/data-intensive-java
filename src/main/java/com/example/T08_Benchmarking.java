package com.example;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * T08: Benchmarking Java Code Correctly
 *
 * Micro-benchmarking Java is notoriously tricky due to JIT compilation, GC pauses,
 * and CPU effects. The 1BRC uses hyperfine (external tool) for end-to-end measurement.
 * For micro-benchmarks, JMH is the gold standard.
 *
 * This example demonstrates:
 * 1. Common benchmarking pitfalls and how to avoid them
 * 2. Manual benchmarking with warmup and statistical analysis
 * 3. How 1BRC's evaluation methodology ensures fair comparison
 *
 * For production use, prefer JMH (@Benchmark annotations).
 * This file shows the concepts without requiring the JMH harness.
 */
public class T08_Benchmarking {

    public static void main(String[] args) {
        System.out.println("=== Benchmarking Java Code ===\n");

        // Pitfall 1: Dead code elimination
        System.out.println("--- Pitfall 1: Dead Code Elimination ---\n");
        demoDCE();

        // Pitfall 2: Not warming up
        System.out.println("\n--- Pitfall 2: Cold vs Warm JIT ---\n");
        demoWarmup();

        // Pitfall 3: GC noise
        System.out.println("\n--- Pitfall 3: GC Interference ---\n");
        demoGcNoise();

        // Correct approach: statistical benchmarking
        System.out.println("\n--- Correct Approach: Statistical Benchmarking ---\n");
        demoStatisticalBenchmark();

        // 1BRC evaluation methodology
        System.out.println("\n--- 1BRC Evaluation Methodology ---\n");
        demo1brcMethodology();
    }

    /**
     * Pitfall: The JIT compiler can eliminate code whose result is never used.
     * If you don't consume the result, you're benchmarking nothing.
     */
    static void demoDCE() {
        int iterations = 10_000_000;

        // BAD: result is not used — JIT may eliminate the entire loop
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Math.sin(i);  // Result discarded — JIT can remove this
        }
        long badTime = System.nanoTime() - start;

        // GOOD: result is consumed (or use JMH's Blackhole)
        long checksum = 0;
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            checksum += Double.doubleToLongBits(Math.sin(i));
        }
        long goodTime = System.nanoTime() - start;

        System.out.printf("Without consuming result: %d ms (may be DCE'd)%n", badTime / 1_000_000);
        System.out.printf("With consuming result:    %d ms (checksum: %d)%n", goodTime / 1_000_000, checksum);
        System.out.println("Fix: Always use the computed result (return it, print it, or use JMH Blackhole)");
    }

    /**
     * Pitfall: First run includes JIT compilation time + interpreter execution.
     * Always discard warmup iterations.
     */
    static void demoWarmup() {
        int iterations = 5_000_000;
        int runs = 8;
        long[] times = new long[runs];

        for (int run = 0; run < runs; run++) {
            long checksum = 0;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                checksum += computeSomething(i);
            }
            times[run] = System.nanoTime() - start;
            if (run == 0) {
                // Force use of checksum
                if (checksum == Long.MIN_VALUE) System.out.print("");
            }
        }

        System.out.println("Run times (ms):");
        for (int i = 0; i < runs; i++) {
            String label = i < 2 ? " (warmup — discard)" : "";
            System.out.printf("  Run %d: %4d ms%s%n", i + 1, times[i] / 1_000_000, label);
        }
        System.out.printf("First run vs last: %.1fx slower%n",
                (double) times[0] / times[runs - 1]);
        System.out.println("Fix: Always include 2-5 warmup iterations before measuring");
    }

    /**
     * Pitfall: GC pauses during measurement add noise.
     */
    static void demoGcNoise() {
        System.out.println("GC state before benchmark:");
        printGcStats();

        // Allocate lots of garbage to trigger GC
        int iterations = 1_000_000;
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            // This creates garbage — strings and arrays
            String s = "station_" + (i % 100) + ";" + (i % 500 / 10.0);
            String[] parts = s.split(";");
            sum += Double.parseDouble(parts[1]) > 0 ? 1 : 0;
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("\nProcessed %d records in %d ms (sum: %d)%n", iterations, elapsed / 1_000_000, sum);
        System.out.println("GC state after benchmark:");
        printGcStats();
        System.out.println("Fix: Use -XX:+UseEpsilonGC for benchmarks, or monitor/report GC time");
    }

    static void printGcStats() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("  %s: %d collections, %d ms total%n",
                    gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }
    }

    /**
     * Correct approach: multiple runs with statistical analysis.
     * This is what 1BRC's evaluate.sh does with hyperfine.
     */
    static void demoStatisticalBenchmark() {
        int iterations = 5_000_000;
        int warmupRuns = 3;
        int measuredRuns = 7;

        // Task A: HashMap with String keys
        long[] timesA = benchmark("HashMap + String.split",
                warmupRuns, measuredRuns, () -> taskHashMap(iterations));

        // Task B: Array-based with integer parsing
        long[] timesB = benchmark("Array + int parsing",
                warmupRuns, measuredRuns, () -> taskArrayBased(iterations));

        System.out.printf("%nTask A median: %d ms%n", median(timesA) / 1_000_000);
        System.out.printf("Task B median: %d ms%n", median(timesB) / 1_000_000);
        System.out.printf("Speedup (median): %.2fx%n", (double) median(timesA) / median(timesB));

        System.out.printf("%nTask A trimmed mean: %d ms%n", trimmedMean(timesA) / 1_000_000);
        System.out.printf("Task B trimmed mean: %d ms%n", trimmedMean(timesB) / 1_000_000);
        System.out.printf("Speedup (trimmed mean): %.2fx%n", (double) trimmedMean(timesA) / trimmedMean(timesB));
    }

    static long[] benchmark(String name, int warmup, int measured, Runnable task) {
        System.out.printf("Benchmarking: %s (%d warmup + %d measured runs)%n", name, warmup, measured);

        // Warmup
        for (int i = 0; i < warmup; i++) task.run();

        // Measured
        long[] times = new long[measured];
        for (int i = 0; i < measured; i++) {
            long start = System.nanoTime();
            task.run();
            times[i] = System.nanoTime() - start;
            System.out.printf("  Run %d: %d ms%n", i + 1, times[i] / 1_000_000);
        }
        return times;
    }

    static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /**
     * Trimmed mean: discard the fastest and slowest, average the rest.
     * This is exactly what 1BRC's evaluate.sh uses.
     */
    static long trimmedMean(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        long sum = 0;
        // Skip first (fastest) and last (slowest)
        for (int i = 1; i < sorted.length - 1; i++) {
            sum += sorted[i];
        }
        return sum / (sorted.length - 2);
    }

    static void taskHashMap(int iterations) {
        Map<String, long[]> map = new HashMap<>();
        Random rng = new Random(42);
        String[] stations = {"A", "B", "C", "D", "E", "F", "G", "H"};
        for (int i = 0; i < iterations; i++) {
            String key = stations[rng.nextInt(stations.length)];
            map.computeIfAbsent(key, k -> new long[4])[0] += rng.nextInt(1000);
        }
    }

    static void taskArrayBased(int iterations) {
        long[][] stats = new long[8][4];
        Random rng = new Random(42);
        for (int i = 0; i < iterations; i++) {
            int idx = rng.nextInt(8);
            stats[idx][0] += rng.nextInt(1000);
        }
    }

    /**
     * Summary of 1BRC's evaluation methodology.
     */
    static void demo1brcMethodology() {
        System.out.println("""
            The 1BRC evaluation is a model for fair, reproducible benchmarking:

            Hardware control:
              - Fixed machine: AMD EPYC 7502P (32 cores), 128 GB RAM
              - SMT disabled (no hyperthreading noise)
              - Turbo Boost disabled (consistent clock speed)
              - CPU pinning via numactl --physcpubind=0-7 (8 cores only)

            Measurement:
              - Tool: hyperfine (external, not JVM-internal)
              - 5 runs per solution
              - Discard fastest + slowest (outlier removal)
              - Report trimmed mean of remaining 3

            Data:
              - File served from RAM disk (tmpfs) — I/O not the bottleneck
              - Same 1B-row file for all solutions

            Key takeaways for your benchmarks:
              1. Pin CPUs to avoid migration noise
              2. Disable turbo boost for consistent results
              3. Use trimmed mean, not single best/worst
              4. Measure end-to-end (including startup for batch jobs)
              5. Use an external tool (hyperfine, JMH) — not System.nanoTime in a loop
              6. Run from RAM disk if you want to isolate CPU-bound work
            """);
    }

    static long computeSomething(int i) {
        return (long) i * i + i * 31 + 17;
    }
}
