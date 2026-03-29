package com.example;

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * T01: Memory-Mapped I/O vs BufferedReader
 *
 * Demonstrates the performance difference between traditional line-by-line
 * reading and memory-mapped file access using the Foreign Memory API.
 *
 * Key insight from 1BRC: Every top solution uses memory-mapped I/O.
 * Files.lines() creates a String object per line, triggering GC pressure.
 * Memory mapping gives zero-copy access to file contents as raw bytes.
 *
 * Based on techniques from: CalculateAverage_thomaswue, CalculateAverage_merykitty
 */
public class T01_MemoryMappedIO {

    private static final int DATA_ROWS = 5_000_000;
    private static final Path DATA_FILE = Path.of("target/t01_data.txt");

    public static void main(String[] args) throws Exception {
        generateTestData();
        System.out.println("Generated " + DATA_ROWS + " rows (" + Files.size(DATA_FILE) / (1024 * 1024) + " MB)");
        System.out.println();

        // Warm up
        long count1 = approach1_BufferedReader();
        long count2 = approach2_MemoryMapped();
        long count3 = approach3_MemoryMappedBulkScan();

        // Timed runs
        System.out.println("=== Approach 1: BufferedReader + String.split() ===");
        long start = System.nanoTime();
        count1 = approach1_BufferedReader();
        long elapsed1 = System.nanoTime() - start;
        System.out.printf("  Stations found: %d, Time: %d ms%n%n", count1, elapsed1 / 1_000_000);

        System.out.println("=== Approach 2: Memory-Mapped + MemorySegment scan ===");
        start = System.nanoTime();
        count2 = approach2_MemoryMapped();
        long elapsed2 = System.nanoTime() - start;
        System.out.printf("  Stations found: %d, Time: %d ms%n%n", count2, elapsed2 / 1_000_000);

        System.out.println("=== Approach 3: Memory-Mapped + bulk byte scanning ===");
        start = System.nanoTime();
        count3 = approach3_MemoryMappedBulkScan();
        long elapsed3 = System.nanoTime() - start;
        System.out.printf("  Stations found: %d, Time: %d ms%n%n", count3, elapsed3 / 1_000_000);

        System.out.printf("Speedup (Approach 2 vs 1): %.1fx%n", (double) elapsed1 / elapsed2);
        System.out.printf("Speedup (Approach 3 vs 1): %.1fx%n", (double) elapsed1 / elapsed3);
    }

    /**
     * Naive approach: BufferedReader with String operations.
     * This is what the 1BRC baseline does — and it's the slowest approach.
     * Every line creates: 1 String (the line), 2 Strings (split result), 1 Double (parseDouble).
     */
    static long approach1_BufferedReader() throws IOException {
        Map<String, long[]> stats = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(DATA_FILE)) {
            String line;
            while ((line = br.readLine()) != null) {
                int sep = line.indexOf(';');
                String station = line.substring(0, sep);
                double temp = Double.parseDouble(line.substring(sep + 1));
                stats.computeIfAbsent(station, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0});
                long[] s = stats.get(station);
                long t = (long) (temp * 10);
                s[0] = Math.min(s[0], t);
                s[1] = Math.max(s[1], t);
                s[2] += t;
                s[3]++;
            }
        }
        return stats.size();
    }

    /**
     * Memory-mapped approach using the Foreign Memory API (Java 21+).
     * The file is mapped into the process address space — no copying, no String allocation.
     * We scan raw bytes to find delimiters and parse numbers directly.
     */
    static long approach2_MemoryMapped() throws IOException {
        Map<String, long[]> stats = new HashMap<>();

        try (FileChannel fc = FileChannel.open(DATA_FILE, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {

            MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
            long size = data.byteSize();
            long offset = 0;

            while (offset < size) {
                // Find semicolon — scan byte by byte
                long semicolon = offset;
                while (data.get(ValueLayout.JAVA_BYTE, semicolon) != ';') {
                    semicolon++;
                }

                // Extract station name as bytes (no String created yet)
                int nameLen = (int) (semicolon - offset);
                byte[] nameBytes = new byte[nameLen];
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, nameBytes, 0, nameLen);
                String station = new String(nameBytes, StandardCharsets.UTF_8);

                // Find newline
                long valueStart = semicolon + 1;
                long lineEnd = valueStart;
                while (lineEnd < size && data.get(ValueLayout.JAVA_BYTE, lineEnd) != '\n') {
                    lineEnd++;
                }

                // Parse temperature as integer (tenths of degree) — avoid Double.parseDouble
                long temp = parseTemperatureAsInt(data, valueStart, lineEnd);

                stats.computeIfAbsent(station, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0});
                long[] s = stats.get(station);
                s[0] = Math.min(s[0], temp);
                s[1] = Math.max(s[1], temp);
                s[2] += temp;
                s[3]++;

                offset = lineEnd + 1;
            }
        }
        return stats.size();
    }

    /**
     * Memory-mapped with bulk scanning: read 8 bytes at a time to find delimiters.
     * This is the foundation for SWAR techniques covered in T05.
     */
    static long approach3_MemoryMappedBulkScan() throws IOException {
        Map<String, long[]> stats = new HashMap<>();

        try (FileChannel fc = FileChannel.open(DATA_FILE, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {

            MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
            long size = data.byteSize();
            long offset = 0;

            while (offset < size) {
                // Find semicolon using getLong for 8-byte-at-a-time scanning
                long semicolon = findByte(data, offset, size, (byte) ';');

                int nameLen = (int) (semicolon - offset);
                byte[] nameBytes = new byte[nameLen];
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, nameBytes, 0, nameLen);
                String station = new String(nameBytes, StandardCharsets.UTF_8);

                long valueStart = semicolon + 1;
                long lineEnd = findByte(data, valueStart, size, (byte) '\n');
                if (lineEnd >= size) lineEnd = size;

                long temp = parseTemperatureAsInt(data, valueStart, lineEnd);

                stats.computeIfAbsent(station, k -> new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 0});
                long[] s = stats.get(station);
                s[0] = Math.min(s[0], temp);
                s[1] = Math.max(s[1], temp);
                s[2] += temp;
                s[3]++;

                offset = lineEnd + 1;
            }
        }
        return stats.size();
    }

    /**
     * Find a target byte by scanning 8 bytes at a time using SWAR.
     * This is a simplified version of what 1BRC winners use.
     */
    static long findByte(MemorySegment data, long offset, long limit, byte target) {
        long pattern = (target & 0xFFL) * 0x0101010101010101L;
        long safeLimit = limit - 7;

        while (offset < safeLimit) {
            long word = data.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
            long xor = word ^ pattern;
            long match = (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
            if (match != 0) {
                return offset + (Long.numberOfTrailingZeros(match) >>> 3);
            }
            offset += 8;
        }
        // Tail: byte-by-byte
        while (offset < limit) {
            if (data.get(ValueLayout.JAVA_BYTE, offset) == target) return offset;
            offset++;
        }
        return limit;
    }

    /**
     * Parse a temperature value like "-12.3" or "5.7" directly from bytes as an integer (tenths).
     * Avoids Double.parseDouble and all its allocation overhead.
     */
    static long parseTemperatureAsInt(MemorySegment data, long start, long end) {
        boolean negative = false;
        long pos = start;
        if (data.get(ValueLayout.JAVA_BYTE, pos) == '-') {
            negative = true;
            pos++;
        }
        long value = 0;
        while (pos < end) {
            byte b = data.get(ValueLayout.JAVA_BYTE, pos);
            if (b == '.') {
                pos++;
                continue;
            }
            value = value * 10 + (b - '0');
            pos++;
        }
        return negative ? -value : value;
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
                String station = stations[rng.nextInt(stations.length)];
                double temp = -30 + rng.nextDouble() * 80;
                w.write(station + ";" + String.format("%.1f", temp));
                w.newLine();
            }
        }
    }
}
