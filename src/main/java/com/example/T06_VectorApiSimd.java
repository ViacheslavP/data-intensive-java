package com.example;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * T06: Java Vector API (SIMD) for Byte Scanning
 *
 * The Vector API (jdk.incubator.vector) provides explicit SIMD operations.
 * Instead of SWAR's 8-byte-at-a-time, vectors process 16, 32, or 64 bytes
 * in a single instruction depending on hardware support (SSE, AVX2, AVX-512).
 *
 * 1BRC usage:
 * - Finding delimiters: ByteVector.compare(EQ, ';') scans 32 bytes at once
 * - Key comparison: Compare hash table keys against input using vector equality
 *
 * Requires: --add-modules jdk.incubator.vector
 *
 * Based on: CalculateAverage_merykitty, CalculateAverage_serkan_ozal
 */
public class T06_VectorApiSimd {

    // Prefer 256-bit (AVX2) if available, fall back to 128-bit (SSE)
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED.length() >= 32
            ? ByteVector.SPECIES_256
            : ByteVector.SPECIES_128;

    public static void main(String[] args) {
        System.out.printf("Vector species: %s (%d bytes / %d bits)%n%n",
                SPECIES, SPECIES.length(), SPECIES.length() * 8);

        // Demo 1: Finding a delimiter in a byte array
        System.out.println("=== Finding delimiters with Vector API ===\n");
        String line = "Hamburg;12.3";
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        // Pad to vector length
        byte[] padded = new byte[SPECIES.length()];
        System.arraycopy(data, 0, padded, 0, data.length);

        var vec = ByteVector.fromArray(SPECIES, padded, 0);
        long mask = vec.compare(VectorOperators.EQ, (byte) ';').toLong();
        int semicolonPos = Long.numberOfTrailingZeros(mask);

        System.out.printf("Input:  \"%s\"%n", line);
        System.out.printf("Vector: loaded %d bytes%n", SPECIES.length());
        System.out.printf("Mask:   0x%X (semicolon at position %d)%n%n", mask, semicolonPos);

        // Demo 2: Vector-based key comparison (merykitty's technique)
        System.out.println("=== Vector key comparison ===\n");
        byte[] key1 = padBytes("Hamburg", SPECIES.length());
        byte[] key2 = padBytes("Hamburg", SPECIES.length());
        byte[] key3 = padBytes("Hamburx", SPECIES.length());

        var v1 = ByteVector.fromArray(SPECIES, key1, 0);
        var v2 = ByteVector.fromArray(SPECIES, key2, 0);
        var v3 = ByteVector.fromArray(SPECIES, key3, 0);

        long eqMask12 = v1.compare(VectorOperators.EQ, v2).toLong();
        long eqMask13 = v1.compare(VectorOperators.EQ, v3).toLong();

        // Only compare up to the key length (7 bytes for "Hamburg")
        long validMask = (1L << 7) - 1; // bits 0-6
        boolean match12 = (eqMask12 & validMask) == validMask;
        boolean match13 = (eqMask13 & validMask) == validMask;

        System.out.printf("\"Hamburg\" vs \"Hamburg\": %s%n", match12 ? "MATCH" : "MISMATCH");
        System.out.printf("\"Hamburg\" vs \"Hamburx\": %s%n%n", match13 ? "MATCH" : "MISMATCH");

        // Demo 3: How merykitty combines delimiter search + key comparison
        System.out.println("=== Combined: search + compare in one pass (merykitty pattern) ===\n");
        demonstrateCombinedLookup();

        // Benchmark
        System.out.println("=== Benchmark: Scalar vs SWAR vs Vector API ===\n");
        benchmarkAll();
    }

    /**
     * merykitty's pattern: load one vector from the file data, use it both to
     * find the delimiter AND to compare the key against the hash table entry.
     */
    static void demonstrateCombinedLookup() {
        // Simulate: we have "Hamburg;12.3" in memory and "Hamburg" stored in hash table
        byte[] fileData = padBytes("Hamburg;12.3", SPECIES.length());
        byte[] storedKey = padBytes("Hamburg", SPECIES.length());

        // Load from file into a vector
        var fileLine = ByteVector.fromArray(SPECIES, fileData, 0);

        // Step 1: Find the semicolon
        long semicolons = fileLine.compare(VectorOperators.EQ, (byte) ';').toLong();
        int keySize = Long.numberOfTrailingZeros(semicolons);
        System.out.printf("  Semicolon found at position %d%n", keySize);

        // Step 2: Compare key against stored entry using the SAME vector load
        var storedVec = ByteVector.fromArray(SPECIES, storedKey, 0);
        long eqMask = fileLine.compare(VectorOperators.EQ, storedVec).toLong();

        // Only bits 0..(keySize-1) must match
        long validMask = semicolons ^ (semicolons - 1); // sets bits 0..keySize
        boolean keyMatches = (eqMask & validMask) == validMask;
        System.out.printf("  Key matches stored entry: %s%n", keyMatches);
        System.out.println("  (One vector load served both delimiter search AND key comparison)");
    }

    static void benchmarkAll() {
        int size = 10_000_000;
        byte[] data = new byte[size + SPECIES.length()]; // extra padding for vector reads
        Random rng = new Random(42);
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ('A' + rng.nextInt(26));
        }
        // Place semicolons every ~50 bytes
        for (int i = 0; i < size; i += 30 + rng.nextInt(40)) {
            data[i] = ';';
        }

        int iterations = 10;

        // Scalar
        long start = System.nanoTime();
        long scalarCount = 0;
        for (int iter = 0; iter < iterations; iter++) {
            int offset = 0;
            while (offset < size) {
                while (offset < size && data[offset] != ';') offset++;
                if (offset < size) { scalarCount++; offset++; }
            }
        }
        long scalarTime = System.nanoTime() - start;

        // SWAR (from T05)
        start = System.nanoTime();
        long swarCount = 0;
        for (int iter = 0; iter < iterations; iter++) {
            int offset = 0;
            while (offset < size) {
                int pos = swarFindByte(data, offset, size, (byte) ';');
                if (pos < 0) break;
                swarCount++;
                offset = pos + 1;
            }
        }
        long swarTime = System.nanoTime() - start;

        // Vector API
        start = System.nanoTime();
        long vectorCount = 0;
        for (int iter = 0; iter < iterations; iter++) {
            int offset = 0;
            while (offset < size) {
                int pos = vectorFindByte(data, offset, size, (byte) ';');
                if (pos < 0) break;
                vectorCount++;
                offset = pos + 1;
            }
        }
        long vectorTime = System.nanoTime() - start;

        System.out.printf("Scalar:     %4d ms  (found %d)%n", scalarTime / 1_000_000, scalarCount / iterations);
        System.out.printf("SWAR:       %4d ms  (found %d)%n", swarTime / 1_000_000, swarCount / iterations);
        System.out.printf("Vector API: %4d ms  (found %d)%n", vectorTime / 1_000_000, vectorCount / iterations);
        System.out.printf("%nSpeedup SWAR vs Scalar:   %.2fx%n", (double) scalarTime / swarTime);
        System.out.printf("Speedup Vector vs Scalar: %.2fx%n", (double) scalarTime / vectorTime);
        System.out.printf("Speedup Vector vs SWAR:   %.2fx%n", (double) swarTime / vectorTime);
    }

    static int vectorFindByte(byte[] data, int offset, int limit, byte target) {
        int vectorLimit = limit - SPECIES.length();
        while (offset <= vectorLimit) {
            var vec = ByteVector.fromArray(SPECIES, data, offset);
            long mask = vec.compare(VectorOperators.EQ, target).toLong();
            if (mask != 0) {
                return offset + Long.numberOfTrailingZeros(mask);
            }
            offset += SPECIES.length();
        }
        // Tail
        while (offset < limit) {
            if (data[offset] == target) return offset;
            offset++;
        }
        return -1;
    }

    static int swarFindByte(byte[] data, int offset, int limit, byte target) {
        long pattern = (target & 0xFFL) * 0x0101010101010101L;
        int safeLimit = limit - 7;
        while (offset < safeLimit) {
            long word = readLong(data, offset);
            long xor = word ^ pattern;
            long match = (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
            if (match != 0) {
                return offset + (Long.numberOfTrailingZeros(match) >>> 3);
            }
            offset += 8;
        }
        while (offset < limit) {
            if (data[offset] == target) return offset;
            offset++;
        }
        return -1;
    }

    static long readLong(byte[] data, int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    static byte[] padBytes(String s, int length) {
        byte[] result = new byte[length];
        byte[] src = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        return result;
    }
}
