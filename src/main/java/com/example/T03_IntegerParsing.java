package com.example;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * T03: Branchless Integer Parsing
 *
 * The 1BRC temperature format is fixed: optional minus, 1-2 digits, dot, 1 digit.
 * Examples: "12.3", "-5.7", "0.0", "-42.1"
 *
 * The naive approach uses Double.parseDouble() which allocates, branches heavily,
 * and handles general cases we don't need. The 1BRC winners parse this as an integer
 * (tenths of a degree) using bit manipulation with ZERO branches.
 *
 * Credit: Quan Anh Mai (merykitty) — original branchless algorithm
 * Used by: thomaswue, artsiomkorzun, jerrinot, and most top solutions
 */
public class T03_IntegerParsing {

    private static final ValueLayout.OfLong JAVA_LONG_LT =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) {
        // Test cases: temperature strings and their expected int values (tenths)
        String[] inputs = {"12.3", "-5.7", "0.0", "-42.1", "99.9", "-99.9", "3.8", "-0.1"};
        int[] expected = {123, -57, 0, -421, 999, -999, 38, -1};

        System.out.println("=== Temperature Parsing Comparison ===\n");

        // Verify correctness
        System.out.println("Testing branchless parser:");
        for (int i = 0; i < inputs.length; i++) {
            int result = parseBranchless(inputs[i]);
            String status = result == expected[i] ? "OK" : "FAIL (expected " + expected[i] + ")";
            System.out.printf("  %-8s => %4d  [%s]%n", inputs[i], result, status);
        }

        System.out.println("\nTesting naive parser:");
        for (int i = 0; i < inputs.length; i++) {
            int result = parseNaive(inputs[i]);
            String status = result == expected[i] ? "OK" : "FAIL (expected " + expected[i] + ")";
            System.out.printf("  %-8s => %4d  [%s]%n", inputs[i], result, status);
        }

        // Benchmark
        int iterations = 50_000_000;
        System.out.println("\n=== Benchmark: " + iterations + " iterations ===\n");

        // Prepare data in a memory segment (simulating file bytes)
        byte[] rawData = new byte[inputs.length * 8]; // 8 bytes per entry, padded
        int[] offsets = new int[inputs.length];
        int pos = 0;
        for (int i = 0; i < inputs.length; i++) {
            offsets[i] = pos;
            byte[] bytes = inputs[i].getBytes();
            System.arraycopy(bytes, 0, rawData, pos, bytes.length);
            rawData[pos + bytes.length] = '\n'; // terminator
            pos += bytes.length + 1;
        }

        // Naive: Double.parseDouble
        long sum = 0;
        long start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            sum += parseNaive(inputs[iter % inputs.length]);
        }
        long naiveTime = System.nanoTime() - start;
        System.out.printf("Naive (Double.parseDouble):  %d ms  (checksum: %d)%n", naiveTime / 1_000_000, sum);

        // Loop-based integer parse
        sum = 0;
        start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            sum += parseLoopBased(inputs[iter % inputs.length]);
        }
        long loopTime = System.nanoTime() - start;
        System.out.printf("Loop-based integer parse:    %d ms  (checksum: %d)%n", loopTime / 1_000_000, sum);

        // Branchless
        sum = 0;
        start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            sum += parseBranchless(inputs[iter % inputs.length]);
        }
        long branchlessTime = System.nanoTime() - start;
        System.out.printf("Branchless (1BRC technique): %d ms  (checksum: %d)%n", branchlessTime / 1_000_000, sum);

        System.out.printf("%nSpeedup (branchless vs naive): %.2fx%n", (double) naiveTime / branchlessTime);
        System.out.printf("Speedup (branchless vs loop):  %.2fx%n", (double) loopTime / branchlessTime);

        System.out.println("\n=== How the branchless algorithm works ===");
        explainAlgorithm("12.3");
        explainAlgorithm("-5.7");
        explainAlgorithm("-42.1");
    }

    /**
     * Naive approach: use Double.parseDouble, then multiply by 10.
     * Allocates a String, handles general cases, branches heavily.
     */
    static int parseNaive(String s) {
        return (int) Math.round(Double.parseDouble(s) * 10.0);
    }

    /**
     * Loop-based approach: avoid Double, but still branches on sign and dot.
     */
    static int parseLoopBased(String s) {
        boolean negative = false;
        int i = 0;
        if (s.charAt(0) == '-') {
            negative = true;
            i = 1;
        }
        int value = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') continue;
            value = value * 10 + (c - '0');
        }
        return negative ? -value : value;
    }

    /**
     * Branchless parsing — the crown jewel of 1BRC optimization.
     *
     * Reads 8 bytes as a long, uses bit manipulation to:
     * 1. Find the decimal point position (without branching)
     * 2. Detect the sign (without branching)
     * 3. Extract and combine digits (without branching)
     *
     * This works because the temperature format is fixed and fits in 8 bytes.
     */
    static int parseBranchless(String s) {
        // In real 1BRC code, this reads directly from memory-mapped file.
        // Here we simulate with a byte array.
        byte[] bytes = new byte[8];
        byte[] src = s.getBytes();
        System.arraycopy(src, 0, bytes, 0, src.length);

        // Read as little-endian long
        long word = 0;
        for (int i = 0; i < 8; i++) {
            word |= ((long) (bytes[i] & 0xFF)) << (i * 8);
        }

        return branchlessParseWord(word);
    }

    /**
     * The core branchless algorithm by Quan Anh Mai (merykitty).
     *
     * The 4th bit of ASCII digits is 1, but for '.' it's 0.
     * This lets us find the decimal separator without any comparison.
     */
    static int branchlessParseWord(long word) {
        // Step 1: Find decimal separator position.
        // ASCII '0'-'9' have bit 4 set (0x30-0x39). '.' (0x2E) does not.
        // By checking ~word & 0x10101000 we find which of the 3 candidate positions has the dot.
        // The three positions correspond to formats: "X.X", "XX.X", "-X.X" / "-XX.X"
        int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);
        // decimalSepPos is 12 for "X.X", 20 for "XX.X" or "-X.X", 28 for "-XX.X"

        // Step 2: Determine sign.
        // '-' is 0x2D. Bit 59 of (~word << 59) will be 1 if first byte is '-'.
        // Arithmetic right shift fills with sign bit, giving -1 (all 1s) for negative, 0 for positive.
        long signed = (~word << 59) >> 63;

        // Step 3: Remove the sign character if present.
        // If negative, mask out the first byte. If positive, keep everything.
        long designMask = ~(signed & 0xFF);

        // Step 4: Align digits and convert from ASCII to digit values.
        // Shift left to align digits to fixed positions, mask to get 0-9 values.
        int shift = 28 - decimalSepPos;
        long digits = ((word & designMask) << shift) & 0x0F000F0F00L;

        // Step 5: Combine digits using multiply-and-shift.
        // digits is now: 0xUU00TTHH00 where UU=units, TT=tens, HH=hundreds
        // Magic multiply: 100 * 0x1000000 + 10 * 0x10000 + 1 = 0x640a0001
        // This computes: HH*100 + TT*10 + UU in bits 32-41 of the product.
        long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;

        // Step 6: Apply sign using XOR trick (no branch).
        // For positive: (value ^ 0) - 0 = value
        // For negative: (value ^ -1) - (-1) = ~value + 1 = -value
        return (int) ((absValue ^ signed) - signed);
    }

    /**
     * Prints a step-by-step trace of the branchless algorithm.
     */
    static void explainAlgorithm(String input) {
        byte[] bytes = new byte[8];
        byte[] src = input.getBytes();
        System.arraycopy(src, 0, bytes, 0, src.length);

        long word = 0;
        for (int i = 0; i < 8; i++) {
            word |= ((long) (bytes[i] & 0xFF)) << (i * 8);
        }

        System.out.printf("%nInput: \"%s\"%n", input);
        System.out.printf("  Raw bytes:     %s%n", formatHex(word));

        int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);
        System.out.printf("  Decimal pos:   bit %d (byte %d)%n", decimalSepPos, decimalSepPos / 8);

        long signed = (~word << 59) >> 63;
        System.out.printf("  Sign mask:     %d (%s)%n", signed, signed == 0 ? "positive" : "negative");

        long designMask = ~(signed & 0xFF);
        int shift = 28 - decimalSepPos;
        long digits = ((word & designMask) << shift) & 0x0F000F0F00L;
        System.out.printf("  Aligned digits: %s%n", formatHex(digits));

        long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
        int result = (int) ((absValue ^ signed) - signed);
        System.out.printf("  Abs value:     %d%n", absValue);
        System.out.printf("  Final result:  %d (= %.1f°)%n", result, result / 10.0);
    }

    static String formatHex(long value) {
        return String.format("0x%016X", value);
    }
}
