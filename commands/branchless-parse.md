Replace number parsing in the target code with branchless bit-manipulation techniques. Target: $ARGUMENTS

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## What to do

1. Read the target file(s)
2. Find all number parsing:
   - **Java**: `Double.parseDouble()`, `Integer.parseInt()`, `Float.parseFloat()`, manual char-by-char digit extraction
   - **Kotlin**: `.toDouble()`, `.toInt()`, `.toFloat()`, `String.format`, manual `Char` digit extraction
3. Determine if the number format is constrained (fixed decimal places, bounded range)
4. Replace with branchless parsing using bit manipulation

## The technique

### Merykitty's branchless temperature parser

For fixed-format numbers like temperatures (`-12.3`, `5.7`, `-0.1`) — optional sign, 1-2 digits, dot, 1 digit:

```java
// Read 8 bytes containing the number
long word = data.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);

// Step 1: Find decimal point position
// ASCII digits (0x30-0x39) have bit 4 set. '.' (0x2E) does not.
int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);

// Step 2: Detect sign (branchless)
// '-' is 0x2D. Bit 59 of (~word << 59) reveals the sign.
long signed = (~word << 59) >> 63;  // -1 if negative, 0 if positive

// Step 3: Mask out sign character
long designMask = ~(signed & 0xFF);

// Step 4: Align digits and convert ASCII → numeric values
int shift = 28 - decimalSepPos;
long digits = ((word & designMask) << shift) & 0x0F000F0F00L;

// Step 5: Magic multiply combines hundreds, tens, units
long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;

// Step 6: Apply sign (XOR trick)
// positive: (v ^ 0) - 0 = v
// negative: (v ^ -1) - (-1) = ~v + 1 = -v
int result = (int) ((absValue ^ signed) - signed);
```

### Store as int, not double

All 1BRC winners store temperatures as `int` in tenths of a degree: `12.3°C` → `123`.

Benefits:
- 4 bytes instead of 8
- Exact arithmetic (no floating-point rounding)
- Integer operations are faster than FP

### Kotlin version of the branchless parser

The bit manipulation is identical — Kotlin compiles to the same JVM bytecode:

```kotlin
fun branchlessParseTemp(data: MemorySegment, offset: Long): Int {
    val word = data.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)
    val decimalSepPos = java.lang.Long.numberOfTrailingZeros(word.inv() and 0x10101000)
    val signed = (word.inv() shl 59) shr 63
    val designMask = (signed and 0xFF).inv()
    val shift = 28 - decimalSepPos
    val digits = ((word and designMask) shl shift) and 0x0F000F0F00L
    val absValue = ((digits * 0x640a0001) ushr 32) and 0x3FF
    return ((absValue xor signed) - signed).toInt()
}
```

**Kotlin note**: Use `shl`/`shr`/`ushr`/`xor`/`and`/`inv()` instead of `<<`/`>>`/`>>>`/`^`/`&`/`~`. The JVM bytecode is identical.

### General branchless patterns

**Branchless absolute value:**
```java
int abs = (value ^ (value >> 31)) - (value >> 31);
```
```kotlin
val abs = (value xor (value shr 31)) - (value shr 31)
```

**Branchless min/max:**
```java
int min = y ^ ((x ^ y) & -(x < y ? 1 : 0));
int max = x ^ ((x ^ y) & -(x < y ? 1 : 0));
```
```kotlin
val min = y xor ((x xor y) and -(if (x < y) 1 else 0))
val max = x xor ((x xor y) and -(if (x < y) 1 else 0))
```

**ASCII digit to value (no branch):**
```java
int digit = ch & 0x0F;  // works because '0'-'9' = 0x30-0x39
```
```kotlin
val digit = ch.code and 0x0F
```

### Why branchless matters

Branch mispredictions cost 10-20 CPU cycles each. On random data (mix of positive/negative, 1-2 digit numbers), prediction accuracy drops. At 1 billion iterations, that's billions of wasted cycles. Branchless code has constant execution time regardless of data patterns.

## When to apply

- Fixed-format numeric fields (prices, temperatures, coordinates, timestamps)
- Parsing in tight loops (millions of records/second)
- Number format is constrained and known at compile time

## When NOT to apply

- General-purpose number parsing (scientific notation, arbitrary precision)
- Code that isn't in a hot loop
- When readability is more important than performance

## Reference

See `src/main/java/com/example/T03_IntegerParsing.java` and `docs/03-integer-parsing.md`.

Apply the transformation, adapting the bit manipulation to the specific number format in the code. Show the complete modified code with explanatory comments.
