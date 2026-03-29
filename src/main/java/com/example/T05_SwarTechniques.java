package com.example;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * T05: SWAR — SIMD Within A Register
 *
 * SWAR processes multiple bytes simultaneously using regular 64-bit long operations.
 * Instead of scanning bytes one at a time, we pack 8 bytes into a long and use
 * bit manipulation to find patterns (delimiters, newlines) across all 8 bytes at once.
 *
 * This is the most universally used technique in 1BRC — it works on any JDK without
 * special modules, unlike the Vector API.
 *
 * The core pattern: XOR with a repeated byte pattern, then use subtraction-borrow
 * to detect zero bytes (which were matches).
 *
 * Based on: All top 1BRC solutions (thomaswue, jerrinot, artsiomkorzun, royvanrijn, etc.)
 */
public class T05_SwarTechniques {

    public static void main(String[] args) {
        System.out.println("=== SWAR: Finding bytes in 8-byte words ===\n");

        // Demo 1: Find semicolons
        String line = "Hamburg;12.3\nBerlin;-5.7\n";
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        System.out.println("Input: \"" + line.replace("\n", "\\n") + "\"");
        System.out.println();

        System.out.println("--- Finding semicolons (;) ---");
        findAllOccurrences(data, (byte) ';');

        System.out.println("\n--- Finding newlines (\\n) ---");
        findAllOccurrences(data, (byte) '\n');

        // Demo 2: Step-by-step explanation
        System.out.println("\n=== Step-by-Step: How SWAR delimiter detection works ===\n");
        explainSwar("Hamburg;", (byte) ';');

        System.out.println();
        explainSwar("12345678", (byte) ';'); // no match case

        // Demo 3: Performance comparison
        System.out.println("\n=== Benchmark: byte-by-byte vs SWAR ===\n");
        benchmarkFind();

        // Demo 4: SWAR newline scanning (used in thomaswue's nextNewLine)
        System.out.println("\n=== SWAR Newline Scanner (1BRC nextNewLine pattern) ===\n");
        String multiline = "Hamburg;12.3\nBerlin;-5.7\nTokyo;25.1\nParis;8.2\n";
        byte[] multiData = multiline.getBytes(StandardCharsets.UTF_8);
        System.out.println("Finding line boundaries in: " + multiline.replace("\n", "\\n"));
        int offset = 0;
        int lineNum = 1;
        while (offset < multiData.length) {
            int nl = swarFindByte(multiData, offset, (byte) '\n');
            if (nl < 0) break;
            String lineContent = new String(multiData, offset, nl - offset, StandardCharsets.UTF_8);
            System.out.printf("  Line %d at [%d..%d]: \"%s\"%n", lineNum++, offset, nl, lineContent);
            offset = nl + 1;
        }
    }

    /**
     * The SWAR pattern for finding a target byte in a long word.
     *
     * Algorithm:
     * 1. XOR with the target byte repeated 8 times → matching bytes become 0x00
     * 2. Apply the "has zero byte" test:
     *    (v - 0x0101..01) & ~v & 0x8080..80
     *    This lights up the high bit of any byte that was zero (i.e., a match)
     *
     * Returns a mask where each matching byte has its high bit set.
     * Use Long.numberOfTrailingZeros(mask) >>> 3 to get the byte index.
     */
    static long swarFindPattern(long word, byte target) {
        // Step 1: XOR with repeated target — matches become 0x00
        long pattern = (target & 0xFFL) * 0x0101010101010101L;
        long xor = word ^ pattern;

        // Step 2: Detect zero bytes using the subtraction-borrow trick
        // If a byte is 0x00, subtracting 0x01 borrows from the high bit
        // The & ~xor & 0x80 ensures we only flag bytes that were actually zero
        return (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
    }

    /**
     * Find the position of target byte in data starting from offset.
     * Uses SWAR to check 8 bytes at a time.
     */
    static int swarFindByte(byte[] data, int offset, byte target) {
        // Process 8 bytes at a time
        int limit = data.length - 7;
        while (offset < limit) {
            long word = readLong(data, offset);
            long match = swarFindPattern(word, target);
            if (match != 0) {
                return offset + (Long.numberOfTrailingZeros(match) >>> 3);
            }
            offset += 8;
        }
        // Tail: byte by byte
        while (offset < data.length) {
            if (data[offset] == target) return offset;
            offset++;
        }
        return -1;
    }

    /**
     * Traditional byte-by-byte search for comparison.
     */
    static int scalarFindByte(byte[] data, int offset, byte target) {
        while (offset < data.length) {
            if (data[offset] == target) return offset;
            offset++;
        }
        return -1;
    }

    static void findAllOccurrences(byte[] data, byte target) {
        int offset = 0;
        while (offset < data.length) {
            int pos = swarFindByte(data, offset, target);
            if (pos < 0) break;
            System.out.printf("  Found '%c' at position %d%n", target == '\n' ? 'n' : (char) target, pos);
            offset = pos + 1;
        }
    }

    static void explainSwar(String input, byte target) {
        byte[] bytes = new byte[8];
        byte[] src = input.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(src.length, 8);
        System.arraycopy(src, 0, bytes, 0, len);

        long word = readLong(bytes, 0);
        long pattern = (target & 0xFFL) * 0x0101010101010101L;
        long xor = word ^ pattern;
        long step2 = xor - 0x0101010101010101L;
        long step3 = step2 & ~xor;
        long result = step3 & 0x8080808080808080L;

        System.out.printf("Input string: \"%s\" (looking for '%c' = 0x%02X)%n", input, (char) target, target);
        System.out.printf("  word    = %s  bytes: %s%n", hex(word), bytesStr(bytes));
        System.out.printf("  pattern = %s  (target repeated 8x)%n", hex(pattern));
        System.out.printf("  xor     = %s  (matches become 0x00)%n", hex(xor));
        System.out.printf("  result  = %s%n", hex(result));
        if (result != 0) {
            int pos = Long.numberOfTrailingZeros(result) >>> 3;
            System.out.printf("  Match found at byte position: %d%n", pos);
        } else {
            System.out.println("  No match found in this word");
        }
    }

    static void benchmarkFind() {
        // Create a large byte array with semicolons at various positions
        int size = 10_000_000;
        byte[] data = new byte[size];
        Random rng = new Random(42);
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ('A' + rng.nextInt(26));
        }
        // Place semicolons every ~50 bytes on average
        for (int i = 0; i < size; i += 30 + rng.nextInt(40)) {
            if (i < size) data[i] = ';';
        }

        int iterations = 10;

        // Scalar
        long start = System.nanoTime();
        long scalarCount = 0;
        for (int iter = 0; iter < iterations; iter++) {
            int offset = 0;
            while (offset < size) {
                int pos = scalarFindByte(data, offset, (byte) ';');
                if (pos < 0) break;
                scalarCount++;
                offset = pos + 1;
            }
        }
        long scalarTime = System.nanoTime() - start;

        // SWAR
        start = System.nanoTime();
        long swarCount = 0;
        for (int iter = 0; iter < iterations; iter++) {
            int offset = 0;
            while (offset < size) {
                int pos = swarFindByte(data, offset, (byte) ';');
                if (pos < 0) break;
                swarCount++;
                offset = pos + 1;
            }
        }
        long swarTime = System.nanoTime() - start;

        System.out.printf("Scalar:  %d ms  (found %d semicolons)%n", scalarTime / 1_000_000, scalarCount / iterations);
        System.out.printf("SWAR:    %d ms  (found %d semicolons)%n", swarTime / 1_000_000, swarCount / iterations);
        System.out.printf("Speedup: %.2fx%n", (double) scalarTime / swarTime);
    }

    static long readLong(byte[] data, int offset) {
        long result = 0;
        for (int i = 0; i < 8 && offset + i < data.length; i++) {
            result |= ((long) (data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    static String hex(long v) {
        return String.format("0x%016X", v);
    }

    static String bytesStr(byte[] bytes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            if (bytes[i] >= 32 && bytes[i] < 127) sb.append((char) bytes[i]);
            else sb.append(String.format("%02X", bytes[i]));
        }
        return sb.append("]").toString();
    }
}
