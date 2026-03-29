Move hot-path data structures off-heap to eliminate GC pressure in the target code. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## What to do

1. Read the target file(s)
2. Find heap allocations in hot loops:
   - **Java**: `new` objects, wrapper types, small aggregation objects, temporary arrays
   - **Kotlin**: `data class` instances per record, `List<T>` of value objects, nullable primitives (`Int?`, `Long?`, `Double?`) causing autoboxing, `Pair`/`Triple` in hot loops, `map {}`/`filter {}` creating intermediate collections
3. Replace with off-heap memory using the Foreign Memory API or primitive-only patterns

## The technique

### Principle: primitives, not objects

```java
// BAD: 16B object header + fields + GC tracking
class Stats { double min, max, sum; int count; }

// GOOD: flat primitive storage — no header, no GC
// Store inline: short min (2B) + short max (2B) + int count (4B) + long sum (8B) = 16B
```

**Kotlin-specific traps** — these all allocate on the heap:
```kotlin
// BAD: data class per record = object header + fields per instance
data class Stats(val min: Double, val max: Double, val sum: Double, val count: Int)

// BAD: nullable primitives are boxed (Int? = java.lang.Integer on heap)
var count: Int? = null  // boxed!
var count: Int = 0      // primitive — no boxing

// BAD: Pair/Triple allocate per call
records.map { Pair(it.name, it.temp) }  // N Pair objects

// BAD: intermediate collections from functional chains
records.map { it.temp }.filter { it > 0 }.sum()  // 2 intermediate Lists
```

### Off-heap with Foreign Memory API (Java/Kotlin 21+)

```java
try (Arena arena = Arena.ofConfined()) {
    // Allocate off-heap, cache-line aligned
    long slotCount = 10_000;
    long slotSize = 64; // bytes per slot
    MemorySegment mem = arena.allocate(slotCount * slotSize, 64);

    // Layout offsets
    int OFF_COUNT = 0;   // int (4B)
    int OFF_MIN   = 4;   // int (4B)
    int OFF_MAX   = 8;   // int (4B)
    int OFF_SUM   = 12;  // long (8B)
    int OFF_KEY_LEN = 20; // int (4B)
    int OFF_KEY   = 24;  // bytes (up to 100B)

    // Read/write
    long base = slotIndex * slotSize;
    mem.set(ValueLayout.JAVA_INT, base + OFF_COUNT, count);
    int min = mem.get(ValueLayout.JAVA_INT, base + OFF_MIN);
    mem.set(ValueLayout.JAVA_LONG, base + OFF_SUM, sum);
}
// Memory freed deterministically at arena close — no GC
```

### Arena selection

| Arena type | Thread safety | Use when |
|-----------|--------------|----------|
| `Arena.ofConfined()` | Single thread | Per-thread processing state |
| `Arena.ofShared()` | Thread-safe | Shared data structures, memory-mapped files |
| `Arena.global()` | Thread-safe | Process-lifetime data (use sparingly) |

### Zero-allocation hot loop pattern

The ideal tight loop allocates zero objects:

```java
while (cursor < end) {
    long word = data.get(ValueLayout.JAVA_LONG_UNALIGNED, cursor);  // read 8 bytes
    long mask = findDelimiter(word);                                  // SWAR find
    int hash = hashWord(word & MASKS[pos]);                          // hash raw bytes
    long entryBase = lookupSlot(hash);                               // get slot offset
    int temp = parseNumber(data, cursor + nameLen);                  // branchless parse
    updateSlot(mem, entryBase, temp);                                // update min/max/sum/count
    cursor += lineLen;
}
// Everything is primitives and raw memory offsets — zero objects, zero GC
```

### Replacing common heap patterns

**ArrayList → off-heap buffer:**
```java
// BEFORE
List<Integer> values = new ArrayList<>(); // autoboxing!
for (...) values.add(temperature);

// AFTER
MemorySegment buf = arena.allocate(capacity * 4L, 4);
int pos = 0;
for (...) { buf.set(ValueLayout.JAVA_INT, pos * 4L, temperature); pos++; }
```

**HashMap → off-heap open-addressing map:**
```java
// BEFORE
Map<String, Stats> map = new HashMap<>(); // Entry objects, String keys, Stats objects

// AFTER — all inline in off-heap memory
MemorySegment map = arena.allocate(CAPACITY * SLOT_SIZE, 64);
// Each slot: [count|min|max|sum|keyLen|keyBytes] — zero objects
```

**Array of objects → struct-of-arrays or array-of-structs:**
```java
// BEFORE: array of objects
Stats[] stations = new Stats[10_000]; // 10K objects on heap

// AFTER: struct-of-arrays (better for sequential access)
int[] counts = new int[10_000];
int[] mins = new int[10_000];
int[] maxs = new int[10_000];
long[] sums = new long[10_000];
// Or: single off-heap MemorySegment with offset arithmetic
```

**Kotlin — same pattern:**
```kotlin
// BEFORE: list of data classes
val stations = List(10_000) { Stats(0.0, 0.0, 0.0, 0) } // 10K objects

// AFTER: struct-of-arrays
val counts = IntArray(10_000)
val mins = IntArray(10_000) { Int.MAX_VALUE }
val maxs = IntArray(10_000) { Int.MIN_VALUE }
val sums = LongArray(10_000)
```

**Kotlin off-heap with Arena:**
```kotlin
Arena.ofConfined().use { arena ->
    val mem = arena.allocate(slotCount * slotSize, 64)
    val base = slotIndex * slotSize
    mem.set(ValueLayout.JAVA_INT, base + OFF_COUNT, count)
    val min = mem.get(ValueLayout.JAVA_INT, base + OFF_MIN)
}
// Freed deterministically at .use {} close
```

### sun.misc.Unsafe (legacy, fastest)

```java
// Allocate
long ptr = UNSAFE.allocateMemory(size);
UNSAFE.setMemory(ptr, size, (byte) 0);

// Read/write — no bounds checking
long value = UNSAFE.getLong(ptr + offset);
UNSAFE.putLong(ptr + offset, newValue);

// Must free manually
UNSAFE.freeMemory(ptr);
```

Faster than Foreign Memory API (no bounds checks) but unsafe and being phased out. Prefer Foreign Memory API for new code.

## When to apply

- Processing millions of records per second
- GC pauses visible in profiler or causing latency spikes
- Hot loop allocates objects (even small ones) per iteration
- Need deterministic memory deallocation

## When NOT to apply

- Object count is manageable (thousands, not millions)
- Code readability is more important than raw performance
- Team is unfamiliar with off-heap patterns

## Reference

See `src/main/java/com/example/T07_MemoryManagement.java` and `docs/07-memory-management.md`.

Apply the off-heap transformation to the specific allocation patterns in the target code. Show the complete modified code.
