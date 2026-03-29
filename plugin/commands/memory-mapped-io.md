Transform file I/O in the target code to use memory-mapped I/O for maximum throughput. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## What to do

1. Read the target file(s)
2. Find all file reading code:
   - **Java**: `BufferedReader`, `Files.lines()`, `Scanner`, `InputStream`, `Files.readAllLines()`
   - **Kotlin**: `.useLines {}`, `.readLines()`, `.readText()`, `.bufferedReader()`, `.forEachLine {}`
3. Replace with memory-mapped I/O using the Foreign Memory API (Java/Kotlin 21+)

## The technique

Memory-mapped files map a file directly into virtual address space. Zero copies, zero String allocations, zero GC pressure.

### Core pattern

```java
try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
     Arena arena = Arena.ofShared()) {
    MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
    // Access raw bytes: data.get(ValueLayout.JAVA_BYTE, offset)
    // Access 8 bytes at once: data.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)
}
```

### Arena selection

- `Arena.ofConfined()` — single-thread processing, try-with-resources
- `Arena.ofShared()` — multi-thread access to same mapping
- `Arena.global()` — process-lifetime mapping (use sparingly)

### Key transformations

**Java — BufferedReader line-by-line → byte scanning:**
```java
// BEFORE
try (var reader = Files.newBufferedReader(path)) {
    String line;
    while ((line = reader.readLine()) != null) {
        String[] parts = line.split(";");
        process(parts[0], Double.parseDouble(parts[1]));
    }
}

// AFTER
try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
     Arena arena = Arena.ofConfined()) {
    MemorySegment data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
    long offset = 0;
    long end = data.byteSize();
    while (offset < end) {
        // Find delimiter and newline by scanning raw bytes
        // Parse directly from bytes — no String allocation
    }
}
```

**Kotlin — idiomatic file reading → byte scanning:**
```kotlin
// BEFORE
File("data.csv").useLines { lines ->
    lines.forEach { line ->
        val (name, temp) = line.split(";")
        process(name, temp.toDouble())
    }
}

// AFTER
FileChannel.open(path, StandardOpenOption.READ).use { fc ->
    Arena.ofConfined().use { arena ->
        val data = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena)
        var offset = 0L
        val end = data.byteSize()
        while (offset < end) {
            // Find delimiter and newline by scanning raw bytes
            // Parse directly from bytes — no String allocation
        }
    }
}
```

**Kotlin note**: `.use {}` replaces try-with-resources. `Arena` and `MemorySegment` work identically — they're JDK APIs.

**For large files (>2GB), split into multiple mappings** since `FileChannel.map()` takes an int size:
```java
long fileSize = fc.size();
long chunkSize = Integer.MAX_VALUE; // ~2GB
for (long pos = 0; pos < fileSize; pos += chunkSize) {
    long mapSize = Math.min(chunkSize, fileSize - pos);
    MemorySegment chunk = fc.map(FileChannel.MapMode.READ_ONLY, pos, mapSize, arena);
}
```

## When NOT to apply

- Files smaller than ~100MB (BufferedReader is fine)
- When character encoding conversion is needed (mmap gives raw bytes)
- Files that are modified during reading

## Reference

See `src/main/java/com/example/T01_MemoryMappedIO.java` and `docs/01-memory-mapped-io.md` for working examples.

Apply the transformation, preserving the existing processing logic. Show the complete modified code.
