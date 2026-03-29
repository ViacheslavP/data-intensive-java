# 06: Java Vector API (SIMD)

## Problem

SWAR processes 8 bytes at a time. Modern CPUs have SIMD registers that can process 16 (SSE), 32 (AVX2), or 64 (AVX-512) bytes in a single instruction. The Java Vector API exposes this capability.

## Technique

### Basic usage

```java
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Choose vector width (auto-selects based on hardware)
VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
// Or force a specific width:
VectorSpecies<Byte> SPECIES_256 = ByteVector.SPECIES_256; // AVX2

// Load bytes from memory
var vec = ByteVector.fromMemorySegment(SPECIES, data, offset, ByteOrder.nativeOrder());

// Find semicolons — compares ALL bytes in parallel
long mask = vec.compare(VectorOperators.EQ, (byte) ';').toLong();
int pos = Long.numberOfTrailingZeros(mask); // position of first match
```

### merykitty's combined search + compare

The key insight: one vector load serves **two purposes**:

```java
// Load a vector from the file
var line = ByteVector.fromMemorySegment(SPECIES, data, offset, ByteOrder.nativeOrder());

// Purpose 1: Find the delimiter
long semicolons = line.compare(VectorOperators.EQ, ';').toLong();
int keySize = Long.numberOfTrailingZeros(semicolons);

// Purpose 2: Compare against hash table entry
var storedKey = ByteVector.fromArray(SPECIES, hashTable.keyData, bucket * KEY_SIZE);
long eqMask = line.compare(VectorOperators.EQ, storedKey).toLong();
long validMask = semicolons ^ (semicolons - 1); // bits 0..keySize
boolean match = (eqMask & validMask) == validMask;
```

One load, two operations. No re-reading from memory.

### Vector width selection

merykitty explicitly caps at 256-bit even if 512-bit is available:

```java
VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED.length() >= 32
    ? ByteVector.SPECIES_256
    : ByteVector.SPECIES_128;
```

Why? On many CPUs, 512-bit operations cause frequency throttling that hurts overall throughput.

## SWAR vs Vector API

| Aspect | SWAR | Vector API |
|--------|------|-----------|
| Bytes per op | 8 | 16-64 |
| JDK requirement | Any | 21+ with `--add-modules jdk.incubator.vector` |
| Readability | Low (bit magic) | High (named operations) |
| Portability | Universal | Hardware-dependent width |
| In 1BRC | All solutions | merykitty, serkan_ozal |

## Requirements

```xml
<compilerArg>--add-modules</compilerArg>
<compilerArg>jdk.incubator.vector</compilerArg>
```

The Vector API is still in incubator as of JDK 21. It may change in future releases, but the concepts and patterns are stable.

## When to use

- High-throughput byte scanning (parsing, searching)
- When you need more than 8x parallelism and hardware supports wider SIMD
- When readability matters more than universality

## References

- `CalculateAverage_merykitty.java` lines 225-276 — iterate() with vector search + compare
- `CalculateAverage_merykitty.java` lines 36-39 — species selection
- Example: `T06_VectorApiSimd.java`
