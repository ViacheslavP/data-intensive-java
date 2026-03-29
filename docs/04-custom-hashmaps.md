# 04: Custom Hash Maps

## Problem

`java.util.HashMap<String, Stats>` involves:
- **Boxing**: Each entry is an `Entry` object with key/value pointers, hash, next pointer
- **Pointer chasing**: Bucket → Entry → Key → equals comparison → value
- **Cache misses**: At least 2-3 memory dereferences per lookup, scattered across heap
- **GC pressure**: Entry objects, String keys, boxed values — all on the heap

For 1 billion lookups, this overhead adds up to seconds.

## Technique

### Open-addressing with linear probing

Replace HashMap's linked-list buckets with a flat array of fixed-size slots:

```
Slot layout (128 bytes, cache-aligned):
┌──────┬──────┬──────┬──────┬────────┬─────────────────┐
│ min  │ max  │count │ sum  │key_len │  key_bytes       │
│ 2B   │ 2B   │ 4B   │ 8B   │ 4B     │  up to 100B     │
└──────┴──────┴──────┴──────┴────────┴─────────────────┘
```

Everything inline. One cache line fetch gets you the key AND the values.

### Collision resolution

```java
int idx = hash & (CAPACITY - 1);  // CAPACITY must be power of 2
while (true) {
    if (slots[idx].isEmpty()) return insertAt(idx);
    if (slots[idx].keyEquals(searchKey)) return slots[idx];
    idx = (idx + 1) & MASK;  // linear probe — CPU prefetch friendly
}
```

Linear probing is preferable over quadratic probing because sequential memory access patterns benefit from hardware prefetching.

### Inline key comparison

For short keys (≤15 bytes), store the entire key as 2 `long` values:

```java
// jerrinot's fast map: compare first 8 + last 8 bytes
if (namePart1 == maskedFirstWord && namePart2 == maskedLastWord) {
    return basePtr;  // Match! 2 long comparisons instead of byte-by-byte
}
```

### Dual map strategy (jerrinot)

- **Fast map**: For keys ≤15 bytes — stores key inline as 2 longs
- **Slow map**: For keys >15 bytes — stores pointer to key in separate buffer
- ~75% of 1BRC station names fit in the fast map

### Hash functions

merykitty uses **FxHash**:
```java
(Integer.rotateLeft(x * 0x9E3779B9, 5) ^ y) * 0x9E3779B9
```

jerrinot uses mtopolnik's hash:
```java
word * 0x517cc1b727220a95L; Long.rotateLeft(result, 17);
```

Both are extremely fast (2-3 instructions) and produce good enough distribution for ≤10K keys.

## When to use

- Known or bounded key cardinality (can size the table statically)
- Hot-path lookups in data processing pipelines
- When keys are byte sequences (not requiring String allocation)
- When HashMap shows up in your profiler as a hot spot

## References

- `CalculateAverage_merykitty.java` lines 59-163 — PoorManMap
- `CalculateAverage_jerrinot.java` lines 614-639 — fast map with inline keys
- `CalculateAverage_jerrinot.java` lines 641-667 — slow map with pointer keys
- Example: `T04_CustomHashMap.java`
