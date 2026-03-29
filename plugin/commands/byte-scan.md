Replace byte-by-byte scanning in the target code with bulk byte operations. Choose the best approach based on the target environment. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## What to do

1. Read the target file(s)
2. Find byte-by-byte scanning:
   - **Java**: `indexOf`, `charAt` loops, byte comparison loops, delimiter searching
   - **Kotlin**: `indexOf`, `split`, `Regex` matching, `forEachIndexed` on bytes, `String.toByteArray()` scanning
3. Choose the right approach based on constraints, then apply it

## Decision: which approach?

- **JDK 21+ with incubator modules allowed** → Use Vector API (32-64 bytes/op). This is the fastest option.
- **Any JDK, or can't use incubator modules** → Use SWAR (8 bytes/op). Universal, zero dependencies.
- **Both needed** → Implement Vector API as primary path with SWAR fallback.

---

## Approach 1: SWAR (universal, any JDK)

Process 8 bytes at once using regular `long` arithmetic. No imports, no modules, works everywhere.

### Core algorithm

```java
/**
 * Returns a mask with bit 7 set in each byte position that matches 'target'.
 * Zero result means no match in this 8-byte word.
 */
static long swarFind(long word, byte target) {
    // XOR with target repeated 8 times — matching bytes become 0x00
    long pattern = (target & 0xFFL) * 0x0101010101010101L;
    long xor = word ^ pattern;

    // Detect zero bytes using subtraction-borrow trick:
    // 0x00 - 0x01 = 0xFF (borrows from bit 7)
    // The & ~xor & 0x80 ensures only actual zero bytes are flagged
    return (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
}

// Extract position of first match (0-7)
long mask = swarFind(word, (byte) ';');
if (mask != 0) {
    int byteIndex = Long.numberOfTrailingZeros(mask) >>> 3;
}
```

### How the zero-byte detection works

For each byte in `xor`:
- `0x00` (match): `0x00 - 0x01 = 0xFF` (borrows), `0xFF & ~0x00 & 0x80 = 0x80` → flagged
- `0x01-0x7F` (no match): subtraction doesn't set bit 7, OR ~byte has bit 7 clear → not flagged
- `0x80-0xFF` (no match): `~byte` has bit 7 clear → not flagged

### SWAR buffer scan

```java
long offset = startOffset;
while (offset + 8 <= endOffset) {
    long word = data.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    long mask = swarFind(word, (byte) ';');
    if (mask != 0) {
        int pos = Long.numberOfTrailingZeros(mask) >>> 3;
        processField(data, fieldStart, offset + pos);
        fieldStart = offset + pos + 1;
        offset = fieldStart;
    } else {
        offset += 8;
    }
}
// Scalar fallback for remaining < 8 bytes
while (offset < endOffset) {
    if (data.get(ValueLayout.JAVA_BYTE, offset) == ';') {
        processField(data, fieldStart, offset);
        fieldStart = offset + 1;
    }
    offset++;
}
```

### Kotlin SWAR

The bit manipulation is identical — use `xor`/`and`/`inv()` instead of `^`/`&`/`~`:

```kotlin
fun swarFind(word: Long, target: Byte): Long {
    val pattern = (target.toLong() and 0xFFL) * 0x0101010101010101L
    val xor = word xor pattern
    return (xor - 0x0101010101010101L) and xor.inv() and 0x8080808080808080uL.toLong()
}
```

### Common delimiter patterns

```java
static final long SEMICOLON = 0x3B3B3B3B3B3B3B3BL;
static final long NEWLINE   = 0x0A0A0A0A0A0A0A0AL;
static final long COMMA     = 0x2C2C2C2C2C2C2C2CL;
static final long TAB       = 0x0909090909090909L;
static final long SPACE     = 0x2020202020202020L;
static final long QUOTE     = 0x2222222222222222L;
// General: (b & 0xFFL) * 0x0101010101010101L
```

### SWAR masking for hash computation

```java
static final long[] MASKS = {
    0x0000000000000000L, 0x00000000000000FFL, 0x000000000000FFFFL,
    0x0000000000FFFFFFL, 0x00000000FFFFFFFFL, 0x000000FFFFFFFFFFL,
    0x0000FFFFFFFFFFFFL, 0x00FFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL
};
long maskedWord = word & MASKS[delimiterPosition];
long hash = maskedWord * 0x517cc1b727220a95L;
```

---

## Approach 2: Vector API (JDK 21+, fastest)

Process 32-64 bytes per instruction using hardware SIMD registers.

### Setup

```java
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Cap at 256-bit (AVX2) — 512-bit causes frequency throttling on many CPUs
static final VectorSpecies<Byte> SPECIES =
    ByteVector.SPECIES_PREFERRED.length() >= 32
        ? ByteVector.SPECIES_256
        : ByteVector.SPECIES_128;
static final int VECTOR_LEN = SPECIES.length(); // 32 for AVX2
```

### Vector buffer scan

```java
long offset = startOffset;
while (offset + VECTOR_LEN <= endOffset) {
    var vec = ByteVector.fromMemorySegment(SPECIES, data, offset, ByteOrder.nativeOrder());
    long mask = vec.compare(VectorOperators.EQ, (byte) ';').toLong();

    if (mask != 0) {
        int pos = Long.numberOfTrailingZeros(mask);
        processField(data, fieldStart, offset + pos);
        fieldStart = offset + pos + 1;
        offset = fieldStart;
    } else {
        offset += VECTOR_LEN; // Skip 32-64 bytes at once
    }
}
// SWAR fallback for remaining bytes (or scalar)
```

### Combined search + key compare (merykitty's dual-use pattern)

One vector load serves two purposes — find delimiter AND compare against hash table entry:

```java
var line = ByteVector.fromMemorySegment(SPECIES, data, offset, ByteOrder.nativeOrder());

// Purpose 1: Find delimiter
long semicolons = line.compare(VectorOperators.EQ, (byte) ';').toLong();
int keySize = Long.numberOfTrailingZeros(semicolons);

// Purpose 2: Compare key against hash table entry (same vector, no re-read)
var storedKey = ByteVector.fromArray(SPECIES, hashTable.keyData, bucket * KEY_SIZE);
long eqMask = line.compare(VectorOperators.EQ, storedKey).toLong();
long validMask = semicolons ^ (semicolons - 1); // bits 0..keySize-1
boolean match = (eqMask & validMask) == validMask;
```

### Multi-delimiter search

```java
long semicolons = vec.compare(VectorOperators.EQ, (byte) ';').toLong();
long newlines = vec.compare(VectorOperators.EQ, (byte) '\n').toLong();
long anyDelimiter = semicolons | newlines;
```

### Build requirements

```xml
<compilerArgs>
    <arg>--add-modules</arg>
    <arg>jdk.incubator.vector</arg>
</compilerArgs>
```

```bash
java --add-modules jdk.incubator.vector -cp ... MainClass
```

---

## Comparison

| Aspect | Scalar | SWAR | Vector API |
|--------|--------|------|-----------|
| Bytes per op | 1 | 8 | 32-64 |
| JDK requirement | Any | Any | 21+ incubator |
| Portability | Universal | Universal | Hardware-dependent |
| Readability | High | Low | Medium |
| Typical speedup | 1x | 2-4x | 4-8x |

## Real-world applications

- CSV/TSV parsing (commas, tabs, newlines)
- JSON tokenization (`{`, `}`, `"`, `:`)
- Log file processing (spaces, brackets)
- Network protocol parsing (delimiters in packet data)
- Any byte-scanning workload on raw buffers

## When NOT to apply

- Searching for multi-byte patterns (these techniques find single bytes)
- Small buffers where scalar scanning is fast enough
- Non-byte-oriented processing

## Reference

See `src/main/java/com/example/T05_SwarTechniques.java`, `src/main/java/com/example/T06_VectorApiSimd.java`, `docs/05-swar-techniques.md`, and `docs/06-vector-api-simd.md`.

Apply the transformation, choosing the right approach for the target environment. Show the complete modified code.
