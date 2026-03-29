package com.example;

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T02: Parallel Processing Strategies
 *
 * Compares three parallelism strategies from the 1BRC:
 * 1. ConcurrentHashMap with parallel streams (simple but contended)
 * 2. Thread-per-core with per-thread local maps + final merge (1BRC pattern)
 * 3. Work-stealing with atomic cursor (what winners actually use)
 *
 * Key insight: Per-thread state with final merge beats shared concurrent structures.
 * The work-stealing pattern ensures even load distribution across threads.
 *
 * Based on: CalculateAverage_thomaswue (work stealing), CalculateAverage_jerrinot (dual processing)
 */
public class T02_ParallelProcessing {

    private static final int DATA_ROWS = 10_000_000;
    private static final Path DATA_FILE = Path.of("target/t02_data.txt");

    public static void main(String[] args) throws Exception {
        generateTestData();
        long fileSize = Files.size(DATA_FILE);
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.printf("File: %d MB, Available cores: %d%n%n", fileSize / (1024 * 1024), cores);

        // Warmup
        approach1_SharedConcurrentMap();
        approach2_PerThreadMerge();
        approach3_WorkStealing();

        System.out.println("=== Approach 1: ConcurrentHashMap (shared state) ===");
        long start = System.nanoTime();
        int count1 = approach1_SharedConcurrentMap();
        long elapsed1 = System.nanoTime() - start;
        System.out.printf("  Stations: %d, Time: %d ms%n%n", count1, elapsed1 / 1_000_000);

        System.out.println("=== Approach 2: Per-thread maps + merge ===");
        start = System.nanoTime();
        int count2 = approach2_PerThreadMerge();
        long elapsed2 = System.nanoTime() - start;
        System.out.printf("  Stations: %d, Time: %d ms%n%n", count2, elapsed2 / 1_000_000);

        System.out.println("=== Approach 3: Work-stealing (1BRC winner pattern) ===");
        start = System.nanoTime();
        int count3 = approach3_WorkStealing();
        long elapsed3 = System.nanoTime() - start;
        System.out.printf("  Stations: %d, Time: %d ms%n%n", count3, elapsed3 / 1_000_000);

        System.out.printf("Speedup (Approach 2 vs 1): %.2fx%n", (double) elapsed1 / elapsed2);
        System.out.printf("Speedup (Approach 3 vs 1): %.2fx%n", (double) elapsed1 / elapsed3);
    }

    /**
     * Approach 1: Multiple threads sharing a ConcurrentHashMap.
     * Simple but suffers from lock contention on the shared map.
     */
    static int approach1_SharedConcurrentMap() throws Exception {
        ConcurrentHashMap<String, long[]> stats = new ConcurrentHashMap<>();

        try (FileChannel fc = FileChannel.open(DATA_FILE, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
            long size = data.byteSize();
            int cores = Runtime.getRuntime().availableProcessors();
            long chunkSize = size / cores;

            Thread[] threads = new Thread[cores];
            for (int i = 0; i < cores; i++) {
                long chunkStart = i * chunkSize;
                long chunkEnd = (i == cores - 1) ? size : (i + 1) * chunkSize;

                // Align to line boundaries
                if (chunkStart > 0) {
                    while (chunkStart < size && data.get(ValueLayout.JAVA_BYTE, chunkStart - 1) != '\n')
                        chunkStart++;
                }
                if (chunkEnd < size) {
                    while (chunkEnd < size && data.get(ValueLayout.JAVA_BYTE, chunkEnd - 1) != '\n')
                        chunkEnd++;
                }

                long finalStart = chunkStart;
                long finalEnd = chunkEnd;
                threads[i] = new Thread(() -> processChunkShared(data, finalStart, finalEnd, stats));
                threads[i].start();
            }
            for (Thread t : threads) t.join();
        }
        return stats.size();
    }

    static void processChunkShared(MemorySegment data, long start, long end, ConcurrentHashMap<String, long[]> stats) {
        long offset = start;
        while (offset < end) {
            long semi = offset;
            while (data.get(ValueLayout.JAVA_BYTE, semi) != ';') semi++;
            byte[] name = new byte[(int) (semi - offset)];
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, name, 0, name.length);
            String station = new String(name, StandardCharsets.UTF_8);

            long valStart = semi + 1;
            long lineEnd = valStart;
            while (lineEnd < end && data.get(ValueLayout.JAVA_BYTE, lineEnd) != '\n') lineEnd++;
            long temp = parseTemp(data, valStart, lineEnd);

            // Contention point: multiple threads competing for the same map
            stats.compute(station, (k, v) -> {
                if (v == null) v = new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0};
                v[0] = Math.min(v[0], temp);
                v[1] = Math.max(v[1], temp);
                v[2] += temp;
                v[3]++;
                return v;
            });
            offset = lineEnd + 1;
        }
    }

    /**
     * Approach 2: Each thread has its own local HashMap — no contention during processing.
     * Results are merged sequentially at the end. This is what most 1BRC solutions do.
     */
    static int approach2_PerThreadMerge() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        try (FileChannel fc = FileChannel.open(DATA_FILE, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
            long size = data.byteSize();
            long chunkSize = size / cores;

            Thread[] threads = new Thread[cores];
            @SuppressWarnings("unchecked")
            Map<String, long[]>[] localMaps = new HashMap[cores];

            for (int i = 0; i < cores; i++) {
                long chunkStart = i * chunkSize;
                long chunkEnd = (i == cores - 1) ? size : (i + 1) * chunkSize;

                if (chunkStart > 0) {
                    while (chunkStart < size && data.get(ValueLayout.JAVA_BYTE, chunkStart - 1) != '\n')
                        chunkStart++;
                }
                if (chunkEnd < size) {
                    while (chunkEnd < size && data.get(ValueLayout.JAVA_BYTE, chunkEnd - 1) != '\n')
                        chunkEnd++;
                }

                long finalStart = chunkStart;
                long finalEnd = chunkEnd;
                int idx = i;
                threads[i] = new Thread(() -> localMaps[idx] = processChunkLocal(data, finalStart, finalEnd));
                threads[i].start();
            }
            for (Thread t : threads) t.join();

            // Merge phase: sequential, no contention
            Map<String, long[]> merged = new HashMap<>();
            for (Map<String, long[]> local : localMaps) {
                if (local == null) continue;
                local.forEach((k, v) -> merged.merge(k, v, (a, b) -> {
                    a[0] = Math.min(a[0], b[0]);
                    a[1] = Math.max(a[1], b[1]);
                    a[2] += b[2];
                    a[3] += b[3];
                    return a;
                }));
            }
            return merged.size();
        }
    }

    static Map<String, long[]> processChunkLocal(MemorySegment data, long start, long end) {
        Map<String, long[]> stats = new HashMap<>();
        long offset = start;
        while (offset < end) {
            long semi = offset;
            while (data.get(ValueLayout.JAVA_BYTE, semi) != ';') semi++;
            byte[] name = new byte[(int) (semi - offset)];
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, name, 0, name.length);
            String station = new String(name, StandardCharsets.UTF_8);

            long valStart = semi + 1;
            long lineEnd = valStart;
            while (lineEnd < end && data.get(ValueLayout.JAVA_BYTE, lineEnd) != '\n') lineEnd++;
            long temp = parseTemp(data, valStart, lineEnd);

            long[] s = stats.computeIfAbsent(station, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0});
            s[0] = Math.min(s[0], temp);
            s[1] = Math.max(s[1], temp);
            s[2] += temp;
            s[3]++;
            offset = lineEnd + 1;
        }
        return stats;
    }

    /**
     * Approach 3: Work-stealing with atomic cursor.
     *
     * Instead of pre-assigning equal chunks (which may be unbalanced if lines vary in length),
     * threads grab small segments (2MB) via an atomic counter. Faster threads automatically
     * pick up more work. This is the pattern used by thomaswue and all top 1BRC solutions.
     */
    static int approach3_WorkStealing() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int segmentSize = 2 * 1024 * 1024; // 2MB segments — same as 1BRC winner

        try (FileChannel fc = FileChannel.open(DATA_FILE, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {
            MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
            long size = data.byteSize();
            AtomicLong cursor = new AtomicLong(0);

            Thread[] threads = new Thread[cores];
            @SuppressWarnings("unchecked")
            Map<String, long[]>[] localMaps = new HashMap[cores];

            for (int i = 0; i < cores; i++) {
                int idx = i;
                threads[i] = new Thread(() -> {
                    Map<String, long[]> local = new HashMap<>();
                    while (true) {
                        long segStart = cursor.getAndAdd(segmentSize);
                        if (segStart >= size) break;

                        // Align to line boundaries
                        long start = segStart;
                        if (start > 0) {
                            while (start < size && data.get(ValueLayout.JAVA_BYTE, start - 1) != '\n')
                                start++;
                        }
                        long end = Math.min(segStart + segmentSize, size);
                        if (end < size) {
                            while (end < size && data.get(ValueLayout.JAVA_BYTE, end - 1) != '\n')
                                end++;
                        }

                        // Process this segment into local map
                        long offset = start;
                        while (offset < end) {
                            long semi = offset;
                            while (data.get(ValueLayout.JAVA_BYTE, semi) != ';') semi++;
                            byte[] name = new byte[(int) (semi - offset)];
                            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, name, 0, name.length);
                            String station = new String(name, StandardCharsets.UTF_8);

                            long valStart = semi + 1;
                            long lineEnd = valStart;
                            while (lineEnd < end && data.get(ValueLayout.JAVA_BYTE, lineEnd) != '\n') lineEnd++;
                            long temp = parseTemp(data, valStart, lineEnd);

                            long[] s = local.computeIfAbsent(station, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0});
                            s[0] = Math.min(s[0], temp);
                            s[1] = Math.max(s[1], temp);
                            s[2] += temp;
                            s[3]++;
                            offset = lineEnd + 1;
                        }
                    }
                    localMaps[idx] = local;
                });
                threads[i].start();
            }
            for (Thread t : threads) t.join();

            Map<String, long[]> merged = new HashMap<>();
            for (Map<String, long[]> local : localMaps) {
                if (local == null) continue;
                local.forEach((k, v) -> merged.merge(k, v, (a, b) -> {
                    a[0] = Math.min(a[0], b[0]);
                    a[1] = Math.max(a[1], b[1]);
                    a[2] += b[2];
                    a[3] += b[3];
                    return a;
                }));
            }
            return merged.size();
        }
    }

    static long parseTemp(MemorySegment data, long start, long end) {
        boolean neg = false;
        long pos = start;
        if (data.get(ValueLayout.JAVA_BYTE, pos) == '-') { neg = true; pos++; }
        long val = 0;
        while (pos < end) {
            byte b = data.get(ValueLayout.JAVA_BYTE, pos++);
            if (b == '.') continue;
            val = val * 10 + (b - '0');
        }
        return neg ? -val : val;
    }

    static void generateTestData() throws IOException {
        if (Files.exists(DATA_FILE) && Files.size(DATA_FILE) > 1000) return;
        Files.createDirectories(DATA_FILE.getParent());
        String[] stations = {
                "Hamburg", "Berlin", "Munich", "Tokyo", "Sydney", "London", "Paris",
                "New York", "Moscow", "Beijing", "Cairo", "Lagos", "Nairobi",
                "Buenos Aires", "São Paulo", "Vancouver", "Reykjavik", "Bangkok"
        };
        Random rng = new Random(42);
        try (BufferedWriter w = Files.newBufferedWriter(DATA_FILE)) {
            for (int i = 0; i < DATA_ROWS; i++) {
                w.write(stations[rng.nextInt(stations.length)] + ";" + String.format("%.1f", -30 + rng.nextDouble() * 80));
                w.newLine();
            }
        }
    }
}
