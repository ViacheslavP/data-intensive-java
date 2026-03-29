package com.example;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * T04: Custom Hash Maps for Fixed-Cardinality Datasets
 *
 * java.util.HashMap is general-purpose: resizable, handles arbitrary keys,
 * boxed entries, pointer-based chaining. For data-intensive workloads with
 * known key cardinality, a specialized map can be 5-10x faster.
 *
 * 1BRC winners use open-addressing maps with:
 * - Fixed-size arrays (no resizing overhead)
 * - Inline key storage (no pointer chasing)
 * - Cache-line-aligned slots (predictable memory access)
 * - Linear probing (CPU prefetch-friendly)
 *
 * Based on: CalculateAverage_merykitty (PoorManMap), CalculateAverage_jerrinot (dual fast/slow maps)
 */
public class T04_CustomHashMap {

    public static void main(String[] args) {
        // Generate test keys simulating weather station names
        String[] stations = generateStationNames(500);
        int lookups = 20_000_000;
        Random rng = new Random(42);
        String[] lookupKeys = new String[lookups];
        for (int i = 0; i < lookups; i++) {
            lookupKeys[i] = stations[rng.nextInt(stations.length)];
        }

        System.out.printf("Stations: %d, Lookups: %d%n%n", stations.length, lookups);

        // Warmup
        benchHashMap(lookupKeys);
        benchOpenAddressMap(lookupKeys);
        benchInlineKeyMap(lookupKeys);

        System.out.println("=== Approach 1: java.util.HashMap ===");
        long start = System.nanoTime();
        long sum1 = benchHashMap(lookupKeys);
        long elapsed1 = System.nanoTime() - start;
        System.out.printf("  Time: %d ms (checksum: %d)%n%n", elapsed1 / 1_000_000, sum1);

        System.out.println("=== Approach 2: Open-addressing with byte[] keys ===");
        start = System.nanoTime();
        long sum2 = benchOpenAddressMap(lookupKeys);
        long elapsed2 = System.nanoTime() - start;
        System.out.printf("  Time: %d ms (checksum: %d)%n%n", elapsed2 / 1_000_000, sum2);

        System.out.println("=== Approach 3: Open-addressing with inline keys (1BRC pattern) ===");
        start = System.nanoTime();
        long sum3 = benchInlineKeyMap(lookupKeys);
        long elapsed3 = System.nanoTime() - start;
        System.out.printf("  Time: %d ms (checksum: %d)%n%n", elapsed3 / 1_000_000, sum3);

        System.out.printf("Speedup (Approach 2 vs 1): %.2fx%n", (double) elapsed1 / elapsed2);
        System.out.printf("Speedup (Approach 3 vs 1): %.2fx%n", (double) elapsed1 / elapsed3);

        System.out.println("\n=== Concept Explanation ===");
        System.out.println("""
            HashMap<String, Stats>:
              - Each entry: Entry object (16B header) + key ptr + value ptr + hash + next ptr
              - Key lookup: hash -> bucket -> follow chain -> String.equals() per node
              - Cache misses: at least 2-3 pointer dereferences per lookup

            Open-addressing map (1BRC style):
              - Flat array of fixed-size slots (e.g., 128 bytes each)
              - Each slot: [key_bytes | min | max | sum | count] — all inline
              - Key lookup: hash -> index -> compare bytes in-place -> linear probe if collision
              - Cache misses: usually 1 (the slot itself), key data is right there

            Dual map strategy (jerrinot):
              - "Fast map" for short keys (≤15 bytes): stores key inline in 2 longs
              - "Slow map" for long keys: stores pointer to key in separate buffer
              - 75% of 1BRC station names are ≤15 bytes → fast path dominates
            """);
    }

    static long benchHashMap(String[] keys) {
        HashMap<String, long[]> map = new HashMap<>();
        long sum = 0;
        for (String key : keys) {
            long[] stats = map.computeIfAbsent(key, k -> new long[4]);
            stats[0]++;
            sum += stats[0];
        }
        return sum;
    }

    static long benchOpenAddressMap(String[] keys) {
        OpenAddressMap map = new OpenAddressMap();
        long sum = 0;
        for (String key : keys) {
            long[] stats = map.getOrCreate(key.getBytes(StandardCharsets.UTF_8));
            stats[0]++;
            sum += stats[0];
        }
        return sum;
    }

    static long benchInlineKeyMap(String[] keys) {
        InlineKeyMap map = new InlineKeyMap();
        long sum = 0;
        for (String key : keys) {
            long[] stats = map.getOrCreate(key.getBytes(StandardCharsets.UTF_8));
            stats[0]++;
            sum += stats[0];
        }
        return sum;
    }

    /**
     * Open-addressing hash map with linear probing.
     * Keys stored as byte arrays in a flat buffer — no boxing, no Entry objects.
     * This is the pattern from CalculateAverage_merykitty (PoorManMap).
     */
    static class OpenAddressMap {
        private static final int CAPACITY = 1 << 14; // 16384 slots, must be power of 2
        private static final int MASK = CAPACITY - 1;
        private static final int KEY_SIZE = 104; // max key bytes per slot

        private final byte[] keyData = new byte[CAPACITY * KEY_SIZE];
        private final int[] keyLengths = new int[CAPACITY];
        private final long[][] values = new long[CAPACITY][];

        long[] getOrCreate(byte[] key) {
            int hash = fxHash(key);
            int idx = hash & MASK;

            while (true) {
                if (keyLengths[idx] == 0) {
                    // Empty slot — insert
                    keyLengths[idx] = key.length;
                    System.arraycopy(key, 0, keyData, idx * KEY_SIZE, key.length);
                    values[idx] = new long[4];
                    return values[idx];
                }
                if (keyLengths[idx] == key.length && keysEqual(idx, key)) {
                    return values[idx];
                }
                idx = (idx + 1) & MASK; // linear probe
            }
        }

        private boolean keysEqual(int slotIdx, byte[] key) {
            int offset = slotIdx * KEY_SIZE;
            for (int i = 0; i < key.length; i++) {
                if (keyData[offset + i] != key[i]) return false;
            }
            return true;
        }

        // FxHash — same hash function used in merykitty's solution
        static int fxHash(byte[] key) {
            int seed = 0x9E3779B9;
            int x, y;
            if (key.length >= 4) {
                x = (key[0] & 0xFF) | ((key[1] & 0xFF) << 8) | ((key[2] & 0xFF) << 16) | ((key[3] & 0xFF) << 24);
                int end = key.length - 4;
                y = (key[end] & 0xFF) | ((key[end + 1] & 0xFF) << 8) | ((key[end + 2] & 0xFF) << 16) | ((key[end + 3] & 0xFF) << 24);
            } else {
                x = key[0] & 0xFF;
                y = key[key.length - 1] & 0xFF;
            }
            return (Integer.rotateLeft(x * seed, 5) ^ y) * seed;
        }
    }

    /**
     * Inline key map — stores the first 16 bytes of the key directly in the slot.
     * For short keys (which are common), comparison is just 2 long comparisons.
     * This is the dual-map pattern from CalculateAverage_jerrinot.
     */
    static class InlineKeyMap {
        private static final int CAPACITY = 1 << 14;
        private static final int MASK = CAPACITY - 1;

        // Each slot stores 2 longs for the key (16 bytes inline)
        private final long[] keyWord1 = new long[CAPACITY];
        private final long[] keyWord2 = new long[CAPACITY];
        private final int[] keyLengths = new int[CAPACITY];
        private final long[][] values = new long[CAPACITY][];

        long[] getOrCreate(byte[] key) {
            // Extract first 8 and last 8 bytes as longs for fast comparison
            long w1 = toLong(key, 0, Math.min(key.length, 8));
            long w2 = key.length > 8 ? toLong(key, 8, Math.min(key.length - 8, 8)) : 0;

            int hash = hash(w1);
            int idx = hash & MASK;

            while (true) {
                if (keyLengths[idx] == 0) {
                    keyLengths[idx] = key.length;
                    keyWord1[idx] = w1;
                    keyWord2[idx] = w2;
                    values[idx] = new long[4];
                    return values[idx];
                }
                // Fast comparison: 2 long comparisons instead of byte-by-byte
                if (keyLengths[idx] == key.length && keyWord1[idx] == w1 && keyWord2[idx] == w2) {
                    return values[idx];
                }
                idx = (idx + 1) & MASK;
            }
        }

        // mtopolnik's hash function — high quality, very fast
        static int hash(long word) {
            long seed = 0x517cc1b727220a95L;
            long hash = word * seed;
            return (int) Long.rotateLeft(hash, 17);
        }

        static long toLong(byte[] bytes, int offset, int len) {
            long result = 0;
            for (int i = 0; i < len && offset + i < bytes.length; i++) {
                result |= ((long) (bytes[offset + i] & 0xFF)) << (i * 8);
            }
            return result;
        }
    }

    static String[] generateStationNames(int count) {
        String[] bases = {
                "Hamburg", "Berlin", "Munich", "Tokyo", "Sydney", "London", "Paris",
                "New York", "Moscow", "Beijing", "Cairo", "Lagos", "Nairobi",
                "Buenos Aires", "São Paulo", "Vancouver", "Reykjavik", "Bangkok",
                "Helsinki", "Oslo", "Stockholm", "Copenhagen", "Amsterdam",
                "Rome", "Madrid", "Lisbon", "Dublin", "Edinburgh", "Prague",
                "Vienna", "Warsaw", "Budapest", "Bucharest", "Athens", "Istanbul"
        };
        Set<String> names = new LinkedHashSet<>(List.of(bases));
        int suffix = 1;
        while (names.size() < count) {
            names.add(bases[suffix % bases.length] + "_" + suffix);
            suffix++;
        }
        return names.toArray(new String[0]);
    }
}
