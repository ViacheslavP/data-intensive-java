# 01: Memory-Mapped I/O

## Problem

The standard approach to reading files in Java — `BufferedReader.readLine()` or `Files.lines()` — creates a `String` object for every line. For a billion rows, that's a billion String allocations, each triggering potential GC work. The I/O layer also copies data from kernel buffers into Java heap buffers.

## Technique

**Memory-mapped files** map a file directly into the process's virtual address space. The OS handles paging — data flows directly from disk to memory without intermediate copies. In Java, this gives you a `MemorySegment` (or raw address via Unsafe) that you can read as raw bytes.

```java
// Foreign Memory API (Java 21+)
try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
     Arena arena = Arena.ofShared()) {
    MemorySegment data = fc.map(MapMode.READ_ONLY, 0, fc.size(), arena);
    // data.get(ValueLayout.JAVA_BYTE, offset) — read any byte, zero-copy
}
```

### Why it's faster

| Aspect | BufferedReader | Memory-Mapped |
|--------|---------------|---------------|
| Copies | kernel → Java buffer → String | kernel → page cache (direct access) |
| Allocations | 1 String per line | Zero (read raw bytes) |
| GC pressure | Very high | None |
| Random access | No (sequential only) | Yes (any offset) |

### 1BRC usage

- **thomaswue**: `FileChannel.map()` with `Arena.global()`, accesses bytes via `Unsafe.getLong(address)`
- **merykitty**: `FileChannel.map()` with `Arena.ofShared()`, accesses via `MemorySegment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)`

## When to use

- Files larger than ~100 MB
- When you need to process data as raw bytes (CSV, binary protocols, log files)
- When GC pressure from String allocation is a bottleneck
- When multiple threads need to read different parts of the same file

## When NOT to use

- Small files where BufferedReader is simpler and fast enough
- When you need character encoding handling (memory mapping gives raw bytes)
- Files that change during reading (mmap reflects mutations)

## References

- `CalculateAverage_thomaswue.java` lines 56-59 — mmap with Arena.global()
- `CalculateAverage_merykitty.java` lines 353-355 — mmap with Arena.ofShared()
- Example: `T01_MemoryMappedIO.java`
