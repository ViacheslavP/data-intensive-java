# 07: Memory Management — Off-Heap and Zero-GC Patterns

## Problem

Every Java object lives on the heap and is managed by the garbage collector. In a tight loop processing 1 billion records:
- Object allocation (even small ones) creates GC pressure
- GC pauses introduce latency spikes
- Object headers (16 bytes each) waste memory
- Pointer indirection causes cache misses

## Technique

### Principle: primitives, not objects

All 1BRC winners store aggregation state as raw primitives:

```java
// BAD: object per station
class Stats { double min, max, sum; int count; }  // 16B header + 36B fields

// GOOD: primitive fields in flat array
short min;  // 2 bytes
short max;  // 2 bytes
int count;  // 4 bytes
long sum;   // 8 bytes
// Total: 16 bytes, no header, no GC
```

### Foreign Memory API (Java 21+)

Safe off-heap memory management:

```java
try (Arena arena = Arena.ofConfined()) {
    // Allocate off-heap, aligned to 64 bytes (cache line)
    MemorySegment mem = arena.allocate(size, 64);

    // Read/write with explicit layouts
    mem.set(ValueLayout.JAVA_INT, offset, value);
    int v = mem.get(ValueLayout.JAVA_INT, offset);
}
// Memory freed deterministically here — no GC
```

### Arena patterns

| Arena type | Thread safety | Lifetime | 1BRC usage |
|-----------|--------------|----------|------------|
| `Arena.ofConfined()` | Single thread | try-with-resources | Per-thread processing |
| `Arena.ofShared()` | Multi-thread | try-with-resources | Shared file mapping (merykitty) |
| `Arena.global()` | Multi-thread | Process lifetime | Memory-mapped file (thomaswue) |

### sun.misc.Unsafe (legacy approach)

Used by the top 3 1BRC solutions for maximum performance:

```java
// Allocate raw memory
long ptr = UNSAFE.allocateMemory(size);
UNSAFE.setMemory(ptr, size, (byte) 0);

// Direct read/write — no bounds checking
long value = UNSAFE.getLong(ptr + offset);
UNSAFE.putLong(ptr + offset, newValue);

// Must free manually (no GC, no try-with-resources)
UNSAFE.freeMemory(ptr);
```

Unsafe is faster than Foreign Memory API because it skips bounds checks, but it's:
- Not officially supported
- Being phased out in future JDK versions
- Dangerous if you make off-by-one errors

### Zero-allocation hot loop

The ideal 1BRC hot loop allocates **zero objects**:

```java
// No Strings, no Doubles, no Entry objects
while (cursor < end) {
    long word = UNSAFE.getLong(cursor);        // read 8 bytes
    long mask = findDelimiter(word);           // SWAR: find ';'
    int hash = hashWord(word & MASK[pos]);     // hash first 8 bytes
    long entryPtr = lookupInMap(hash);         // get off-heap struct pointer
    long temp = parseNumber(cursor + nameLen); // branchless parse
    recordTemp(entryPtr, temp);                // update min/max/sum/count
}
```

Every operation works on primitives and raw memory addresses.

## When to use

- Processing millions of records per second
- GC pauses are showing up in your profiler
- You need deterministic memory deallocation (not "eventually" via GC)
- Building custom data structures with specific memory layouts

## When to stick with heap

- Object count is manageable (thousands, not billions)
- Development speed matters more than raw performance
- Code will be maintained by a team unfamiliar with off-heap patterns

## References

- `CalculateAverage_thomaswue.java` lines 390-434 — Unsafe for raw memory access
- `CalculateAverage_jerrinot.java` lines 432-439 — Unsafe.allocateMemory for off-heap maps
- `CalculateAverage_merykitty.java` lines 353-355 — Arena.ofShared() for safe off-heap
- Example: `T07_MemoryManagement.java`
