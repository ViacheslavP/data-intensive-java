Replace HashMap with a custom open-addressing hash map optimized for the target code's access pattern. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## What to do

1. Read the target file(s)
2. Find HashMap usage in hot paths:
   - **Java**: `HashMap<String, ...>`, `Map.of()`, `computeIfAbsent` in aggregation loops
   - **Kotlin**: `mutableMapOf()`, `HashMap()`, `groupBy {}`, `groupingBy {}`, `getOrPut {}` in hot paths
3. Determine key characteristics: max key length, key cardinality, key type (String vs bytes)
4. Build a custom open-addressing map with inline key storage

## The technique

### Why HashMap is slow in hot loops

- **Entry objects**: Each entry = object header (16B) + key pointer + value pointer + hash + next pointer
- **Pointer chasing**: Bucket → Entry → Key → equals → Value = 2-3 cache misses per lookup
- **String keys**: Each String = object header + char[] pointer + hash field + length
- **GC pressure**: Millions of Entry objects and String keys

### Open-addressing with linear probing

```java
class OpenAddressMap {
    static final int CAPACITY = 1 << 14; // power of 2, sized for expected cardinality
    static final int MASK = CAPACITY - 1;
    static final int SLOT_SIZE = 128; // bytes per slot, cache-line aligned

    // Flat array: [min|max|count|sum|keyLen|keyBytes...]
    final byte[] data = new byte[CAPACITY * SLOT_SIZE];

    // Offsets within each slot
    static final int OFF_MIN = 0;      // short (2B)
    static final int OFF_MAX = 2;      // short (2B)
    static final int OFF_COUNT = 4;    // int (4B)
    static final int OFF_SUM = 8;      // long (8B)
    static final int OFF_KEY_LEN = 16; // int (4B)
    static final int OFF_KEY = 20;     // bytes (up to 100B)

    void putOrUpdate(byte[] key, int keyLen, int value) {
        int hash = fxHash(key, keyLen);
        int idx = hash & MASK;
        while (true) {
            int base = idx * SLOT_SIZE;
            int storedLen = getInt(data, base + OFF_KEY_LEN);
            if (storedLen == 0) {
                // Empty slot — insert
                insert(base, key, keyLen, value);
                return;
            }
            if (storedLen == keyLen && keysEqual(data, base + OFF_KEY, key, keyLen)) {
                // Match — update
                update(base, value);
                return;
            }
            idx = (idx + 1) & MASK; // linear probe
        }
    }
}
```

### Fast hash functions

**FxHash** (used by merykitty):
```java
static int fxHash(long word1, long word2) {
    return (Integer.rotateLeft((int)(word1 * 0x9E3779B9), 5) ^ (int)word2) * 0x9E3779B9;
}
```

**mtopolnik's hash** (used by jerrinot):
```java
static int hash(long word) {
    return (int)(word * 0x517cc1b727220a95L) >>> 17;
}
```

### Dual-map strategy (jerrinot)

For mixed key lengths:

```java
// Fast map: keys ≤ 15 bytes — store inline as 2 longs
// Lookup = 2 long comparisons (no byte-by-byte)
if (keyLen <= 15) {
    int idx = hash & FAST_MASK;
    while (true) {
        if (fastMap[idx].word1 == keyWord1 && fastMap[idx].word2 == keyWord2) {
            return fastMap[idx]; // HIT — 2 comparisons
        }
        idx = (idx + 1) & FAST_MASK;
    }
}

// Slow map: keys > 15 bytes — byte array comparison
```

~75% of typical station/city names fit in the fast map.

### Inline key comparison with long words

```java
// Read key as 2 longs (even if shorter — mask the excess)
long word1 = data.get(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset);
long word2 = data.get(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset + 8);
// Mask to actual key length
word1 &= MASK_TABLE_1[keyLen];
word2 &= MASK_TABLE_2[keyLen];
// Compare: 2 long ops instead of byte-by-byte
if (slot.word1 == word1 && slot.word2 == word2) { /* match */ }
```

## Sizing the table

- Capacity should be 2-4x the expected number of unique keys
- Must be power of 2 (for mask-based modulo)
- Load factor > 0.7 degrades linear probing performance

### Kotlin: custom open-addressing map

```kotlin
class OpenAddressMap(private val capacityBits: Int = 14) {
    private val capacity = 1 shl capacityBits
    private val mask = capacity - 1
    private val keys = arrayOfNulls<ByteArray>(capacity)
    private val counts = IntArray(capacity)
    private val mins = IntArray(capacity) { Int.MAX_VALUE }
    private val maxs = IntArray(capacity) { Int.MIN_VALUE }
    private val sums = LongArray(capacity)

    fun putOrUpdate(key: ByteArray, keyLen: Int, value: Int) {
        var idx = fxHash(key, keyLen) and mask
        while (true) {
            val stored = keys[idx]
            if (stored == null) {
                keys[idx] = key.copyOf(keyLen)
                counts[idx] = 1
                mins[idx] = value; maxs[idx] = value; sums[idx] = value.toLong()
                return
            }
            if (stored.size == keyLen && stored.contentEquals(key.sliceArray(0 until keyLen))) {
                counts[idx]++
                if (value < mins[idx]) mins[idx] = value
                if (value > maxs[idx]) maxs[idx] = value
                sums[idx] += value
                return
            }
            idx = (idx + 1) and mask
        }
    }
}
```

**Kotlin note**: For maximum performance, use the same flat-array/MemorySegment approach as Java. The class above is a readable middle ground. For the absolute fastest path, Kotlin and Java emit identical bytecode — write it the same way using `MemorySegment` and offset arithmetic.

## When to apply

- Bounded key cardinality (you know the max number of unique keys)
- Hot-path aggregation loops (millions of lookups)
- Keys are byte sequences (can avoid String allocation)
- HashMap shows up in profiler as hot spot
- **Kotlin-specific**: `groupBy`/`groupingBy` creating intermediate collections in tight loops

## When NOT to apply

- Unbounded or very high key cardinality
- Non-hot-path code where HashMap/`mutableMapOf()` is fine
- When the map needs to support deletion (open-addressing deletion is complex)

## Reference

See `src/main/java/com/example/T04_CustomHashMap.java` and `docs/04-custom-hashmaps.md`.

Build the custom map tailored to the specific key/value types and access patterns in the target code. Show the complete implementation.
