package com.example;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;

/**
 * T07: Memory Management — Off-Heap and Zero-GC Patterns
 *
 * In data-intensive workloads, GC pauses can dominate latency. The 1BRC winners
 * eliminate GC pressure by:
 * 1. Storing all intermediate state as primitives (int, long) — no boxing
 * 2. Using off-heap memory via Unsafe or the Foreign Memory API
 * 3. Avoiding String/Object allocation in hot loops entirely
 *
 * This example demonstrates the Foreign Memory API (Java 21+), the modern
 * and safe alternative to sun.misc.Unsafe for off-heap memory management.
 *
 * Based on: CalculateAverage_thomaswue (Unsafe), CalculateAverage_merykitty (Foreign Memory API)
 */
public class T07_MemoryManagement {

    public static void main(String[] args) {
        System.out.println("=== Memory Management Strategies ===\n");

        // Demo 1: Heap arrays vs off-heap MemorySegment
        System.out.println("--- 1. Heap array vs Foreign Memory API ---\n");
        demoHeapVsOffHeap();

        // Demo 2: Struct-like layout in off-heap memory
        System.out.println("\n--- 2. Struct layout in off-heap memory ---\n");
        demoStructLayout();

        // Demo 3: Benchmark — heap vs off-heap aggregation
        System.out.println("\n--- 3. Benchmark: Heap objects vs off-heap structs ---\n");
        benchmarkAggregation();

        // Demo 4: Arena scoping patterns
        System.out.println("\n--- 4. Arena lifecycle patterns ---\n");
        demoArenaPatterns();
    }

    /**
     * Comparison: heap array vs off-heap MemorySegment for raw data storage.
     */
    static void demoHeapVsOffHeap() {
        int count = 1000;

        // Heap: int array (managed by GC)
        int[] heapArray = new int[count];
        for (int i = 0; i < count; i++) heapArray[i] = i * 10;
        System.out.printf("Heap array: %d ints, sum = %d%n", count, sumHeap(heapArray));

        // Off-heap: MemorySegment (not managed by GC)
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment offHeap = arena.allocate(count * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < count; i++) {
                offHeap.setAtIndex(ValueLayout.JAVA_INT, i, i * 10);
            }
            System.out.printf("Off-heap segment: %d ints, sum = %d%n", count, sumOffHeap(offHeap, count));
            System.out.println("  Off-heap memory is freed when the Arena closes — no GC involved");
        }
        // Arena closed — memory is freed deterministically
    }

    static long sumHeap(int[] arr) {
        long sum = 0;
        for (int v : arr) sum += v;
        return sum;
    }

    static long sumOffHeap(MemorySegment seg, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += seg.getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return sum;
    }

    /**
     * 1BRC pattern: store aggregation state as a flat struct in off-heap memory.
     *
     * Each "entry" in the hash map is a fixed-size struct:
     *   offset 0:  count (int, 4 bytes)
     *   offset 4:  min   (int, 4 bytes)
     *   offset 8:  max   (int, 4 bytes)
     *   offset 12: sum   (long, 8 bytes)
     *   offset 20: keyLen (int, 4 bytes)
     *   offset 24: key   (100 bytes)
     *   Total: 128 bytes per entry (cache-line aligned)
     */
    static void demoStructLayout() {
        int ENTRY_SIZE = 128;
        int COUNT_OFFSET = 0;
        int MIN_OFFSET = 4;
        int MAX_OFFSET = 8;
        int SUM_OFFSET = 12;
        int KEY_LEN_OFFSET = 20;
        int KEY_OFFSET = 24;

        int numEntries = 4;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment entries = arena.allocate((long) numEntries * ENTRY_SIZE, 64);
            // Zero-initialize
            entries.fill((byte) 0);

            // Initialize entry 0 with "Hamburg"
            byte[] name = "Hamburg".getBytes();
            int entryBase = 0;
            entries.set(ValueLayout.JAVA_INT, entryBase + KEY_LEN_OFFSET, name.length);
            MemorySegment.copy(MemorySegment.ofArray(name), 0, entries, entryBase + KEY_OFFSET, name.length);
            entries.set(ValueLayout.JAVA_INT, entryBase + MIN_OFFSET, Integer.MAX_VALUE);
            entries.set(ValueLayout.JAVA_INT, entryBase + MAX_OFFSET, Integer.MIN_VALUE);

            // Simulate recording temperatures: 12.3, -5.7, 8.0 (as tenths)
            int[] temps = {123, -57, 80};
            for (int temp : temps) {
                int count = entries.get(ValueLayout.JAVA_INT, entryBase + COUNT_OFFSET);
                int min = entries.get(ValueLayout.JAVA_INT, entryBase + MIN_OFFSET);
                int max = entries.get(ValueLayout.JAVA_INT, entryBase + MAX_OFFSET);
                long sum = entries.get(ValueLayout.JAVA_LONG, entryBase + SUM_OFFSET);

                entries.set(ValueLayout.JAVA_INT, entryBase + COUNT_OFFSET, count + 1);
                entries.set(ValueLayout.JAVA_INT, entryBase + MIN_OFFSET, Math.min(min, temp));
                entries.set(ValueLayout.JAVA_INT, entryBase + MAX_OFFSET, Math.max(max, temp));
                entries.set(ValueLayout.JAVA_LONG, entryBase + SUM_OFFSET, sum + temp);
            }

            // Read back
            int count = entries.get(ValueLayout.JAVA_INT, entryBase + COUNT_OFFSET);
            int min = entries.get(ValueLayout.JAVA_INT, entryBase + MIN_OFFSET);
            int max = entries.get(ValueLayout.JAVA_INT, entryBase + MAX_OFFSET);
            long sum = entries.get(ValueLayout.JAVA_LONG, entryBase + SUM_OFFSET);
            int keyLen = entries.get(ValueLayout.JAVA_INT, entryBase + KEY_LEN_OFFSET);
            byte[] readName = new byte[keyLen];
            MemorySegment.copy(entries, entryBase + KEY_OFFSET, MemorySegment.ofArray(readName), 0, keyLen);

            System.out.printf("Station: %s%n", new String(readName));
            System.out.printf("  Count: %d, Min: %.1f, Max: %.1f, Mean: %.1f%n",
                    count, min / 10.0, max / 10.0, (sum / 10.0) / count);
            System.out.println("  All data stored in a flat 128-byte struct — zero objects allocated");
        }
    }

    /**
     * Benchmark: Java objects with GC vs off-heap structs without GC.
     */
    static void benchmarkAggregation() {
        int stations = 500;
        int records = 10_000_000;
        Random rng = new Random(42);
        int[] stationIds = new int[records];
        int[] temperatures = new int[records];
        for (int i = 0; i < records; i++) {
            stationIds[i] = rng.nextInt(stations);
            temperatures[i] = -500 + rng.nextInt(1000);
        }

        // Approach 1: Heap objects
        long start = System.nanoTime();
        long heapSum = benchHeapObjects(stations, stationIds, temperatures);
        long heapTime = System.nanoTime() - start;
        System.out.printf("Heap objects: %d ms  (checksum: %d)%n", heapTime / 1_000_000, heapSum);

        // Approach 2: Off-heap structs
        start = System.nanoTime();
        long offHeapSum = benchOffHeapStructs(stations, stationIds, temperatures);
        long offHeapTime = System.nanoTime() - start;
        System.out.printf("Off-heap:     %d ms  (checksum: %d)%n", offHeapTime / 1_000_000, offHeapSum);

        System.out.printf("Speedup: %.2fx%n", (double) heapTime / offHeapTime);
    }

    static long benchHeapObjects(int stations, int[] ids, int[] temps) {
        // Each station gets an object with min/max/sum/count
        int[][] stats = new int[stations][]; // [min, max, count] + long sum stored as 2 ints
        for (int i = 0; i < stations; i++) {
            stats[i] = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 0, 0, 0};
        }

        for (int i = 0; i < ids.length; i++) {
            int[] s = stats[ids[i]];
            int t = temps[i];
            if (t < s[0]) s[0] = t;
            if (t > s[1]) s[1] = t;
            s[2]++;
            s[3] += t; // lower 32 bits of sum
        }

        long checksum = 0;
        for (int[] s : stats) checksum += s[0] + s[1] + s[2] + s[3];
        return checksum;
    }

    static long benchOffHeapStructs(int stations, int[] ids, int[] temps) {
        int ENTRY_SIZE = 24; // min(4) + max(4) + count(4) + padding(4) + sum(8)
        int MIN_OFF = 0, MAX_OFF = 4, COUNT_OFF = 8, SUM_OFF = 16;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mem = arena.allocate((long) stations * ENTRY_SIZE, 8);
            // Initialize min/max
            for (int i = 0; i < stations; i++) {
                long base = (long) i * ENTRY_SIZE;
                mem.set(ValueLayout.JAVA_INT, base + MIN_OFF, Integer.MAX_VALUE);
                mem.set(ValueLayout.JAVA_INT, base + MAX_OFF, Integer.MIN_VALUE);
                mem.set(ValueLayout.JAVA_INT, base + COUNT_OFF, 0);
                mem.set(ValueLayout.JAVA_LONG, base + SUM_OFF, 0L);
            }

            for (int i = 0; i < ids.length; i++) {
                long base = (long) ids[i] * ENTRY_SIZE;
                int t = temps[i];
                int min = mem.get(ValueLayout.JAVA_INT, base + MIN_OFF);
                int max = mem.get(ValueLayout.JAVA_INT, base + MAX_OFF);
                int count = mem.get(ValueLayout.JAVA_INT, base + COUNT_OFF);
                long sum = mem.get(ValueLayout.JAVA_LONG, base + SUM_OFF);

                if (t < min) mem.set(ValueLayout.JAVA_INT, base + MIN_OFF, t);
                if (t > max) mem.set(ValueLayout.JAVA_INT, base + MAX_OFF, t);
                mem.set(ValueLayout.JAVA_INT, base + COUNT_OFF, count + 1);
                mem.set(ValueLayout.JAVA_LONG, base + SUM_OFF, sum + t);
            }

            long checksum = 0;
            for (int i = 0; i < stations; i++) {
                long base = (long) i * ENTRY_SIZE;
                checksum += mem.get(ValueLayout.JAVA_INT, base + MIN_OFF);
                checksum += mem.get(ValueLayout.JAVA_INT, base + MAX_OFF);
                checksum += mem.get(ValueLayout.JAVA_INT, base + COUNT_OFF);
                checksum += mem.get(ValueLayout.JAVA_LONG, base + SUM_OFF);
            }
            return checksum;
        }
    }

    /**
     * Arena patterns used in 1BRC and real applications.
     */
    static void demoArenaPatterns() {
        // 1. Confined arena: single-thread, auto-closed
        System.out.println("1. Arena.ofConfined() — single thread, try-with-resources:");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(1024);
            seg.set(ValueLayout.JAVA_INT, 0, 42);
            System.out.printf("   Allocated 1024 bytes, wrote %d%n", seg.get(ValueLayout.JAVA_INT, 0));
        }
        System.out.println("   Memory freed on close\n");

        // 2. Shared arena: multi-thread safe
        System.out.println("2. Arena.ofShared() — thread-safe, for parallel processing:");
        try (Arena arena = Arena.ofShared()) {
            MemorySegment seg = arena.allocate(1024);
            // Can be accessed from multiple threads safely
            Thread t = new Thread(() -> seg.set(ValueLayout.JAVA_INT, 0, 99));
            t.start();
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.printf("   Written from another thread: %d%n", seg.get(ValueLayout.JAVA_INT, 0));
        }
        System.out.println("   Memory freed on close\n");

        // 3. Global arena: lives forever (used by thomaswue for memory-mapped file)
        System.out.println("3. Arena.global() — never freed, process-lifetime:");
        MemorySegment seg = Arena.global().allocate(64);
        seg.set(ValueLayout.JAVA_INT, 0, 777);
        System.out.printf("   Allocated in global arena: %d%n", seg.get(ValueLayout.JAVA_INT, 0));
        System.out.println("   Used by 1BRC winners for mmap — freed on process exit");
    }
}
