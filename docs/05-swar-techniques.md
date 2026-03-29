# 05: SWAR — SIMD Within A Register

## Problem

Finding a delimiter (`;`, `\n`) byte-by-byte requires one comparison per byte. For 14 GB of data, that's 14 billion comparisons just for delimiter detection.

## Technique

SWAR processes 8 bytes at once using regular `long` operations — no special hardware or API needed.

### The core algorithm

```java
// Find byte 0x3B (';') in a 64-bit word
static long findDelimiter(long word) {
    // Step 1: XOR with ';' repeated 8 times
    // Matching bytes become 0x00, non-matching become non-zero
    long xor = word ^ 0x3B3B3B3B3B3B3B3BL;

    // Step 2: Detect zero bytes using subtraction-borrow trick
    // If a byte is 0x00, subtracting 0x01 causes a borrow from bit 7
    // The & ~xor & 0x80 ensures only actual zero bytes are flagged
    return (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
}
```

### How the zero-byte detection works

For each byte position in `xor`:
- If byte is `0x00` (match): `0x00 - 0x01 = 0xFF` (borrows), `0xFF & ~0x00 & 0x80 = 0x80` → **flagged**
- If byte is `0x01-0x7F`: `(byte - 0x01)` doesn't set bit 7, OR `~byte` has bit 7 clear → not flagged
- If byte is `0x80-0xFF`: `~byte` has bit 7 clear → not flagged

The result is a mask where bit 7 of each byte is set only for matching positions.

### Extracting the position

```java
long mask = findDelimiter(word);
if (mask != 0) {
    // Position of first match (0-7)
    int byteIndex = Long.numberOfTrailingZeros(mask) >>> 3;
}
```

### Repeated byte patterns

Common patterns used in 1BRC:

| Target | Pattern constant |
|--------|-----------------|
| `;` (0x3B) | `0x3B3B3B3B3B3B3B3BL` |
| `\n` (0x0A) | `0x0A0A0A0A0A0A0A0AL` |
| Any byte `b` | `(b & 0xFFL) * 0x0101010101010101L` |

### Masking for hash computation

Once the delimiter position is known, mask the word to keep only key bytes:

```java
// thomaswue's MASK1 lookup table
long[] MASK1 = {0xFFL, 0xFFFFL, 0xFFFFFFL, 0xFFFFFFFFL, ...};
long maskedWord = word & MASK1[delimiterPosition];
```

This avoids branching and gives a clean value for hashing.

## Real-world applications

- CSV/TSV parsing (find commas, tabs)
- JSON tokenization (find `{`, `}`, `"`, `:`)
- Log file processing (find spaces, brackets)
- Network protocol parsing (find delimiters in packet data)
- String search (find character in byte buffer)

## When to use

- Any byte-scanning workload on raw data
- Works on all JDKs (no incubator modules needed)
- Most beneficial when delimiters are sparse (many bytes between matches)

## References

- `CalculateAverage_thomaswue.java` line 322-325 — findDelimiter
- `CalculateAverage_thomaswue.java` lines 267-281 — nextNewLine (SWAR newline scan)
- `CalculateAverage_jerrinot.java` lines 274-278 — getDelimiterMask
- Example: `T05_SwarTechniques.java`
