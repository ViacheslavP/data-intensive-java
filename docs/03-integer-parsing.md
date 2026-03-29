# 03: Branchless Integer Parsing

## Problem

`Double.parseDouble()` is a general-purpose parser that handles scientific notation, infinity, NaN, and arbitrary precision. For fixed-format numbers like temperatures (`-12.3`, `5.7`), this is massive overkill. It also allocates internally and branches heavily — both killers in tight loops.

## Technique

The temperature format is constrained: optional `-`, 1-2 digits, `.`, 1 digit. This always fits in 8 bytes. The algorithm by **Quan Anh Mai (merykitty)** parses this using only bit manipulation — **zero branches**.

### Step-by-step

```java
// Read 8 bytes starting at the number
long word = UNSAFE.getLong(pos);

// Step 1: Find decimal point position
// ASCII digits have bit 4 set (0x30-0x39), '.' (0x2E) does not
int decimalSepPos = Long.numberOfTrailingZeros(~word & 0x10101000);

// Step 2: Detect sign (branchless)
// '-' is 0x2D → bit 59 of (~word << 59) reveals the sign
long signed = (~word << 59) >> 63;  // -1 if negative, 0 if positive

// Step 3: Mask out sign character
long designMask = ~(signed & 0xFF);

// Step 4: Align digits and convert ASCII → values
int shift = 28 - decimalSepPos;
long digits = ((word & designMask) << shift) & 0x0F000F0F00L;

// Step 5: Magic multiply combines hundreds, tens, units
// 0xUU00TTHH00 * 0x640a0001 = value in bits 32-41
long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;

// Step 6: Apply sign (XOR trick)
int result = (int) ((absValue ^ signed) - signed);
```

### Why branchless matters

Modern CPUs predict branches. When prediction fails (e.g., random mix of positive/negative temperatures), the pipeline is flushed — costing 10-20 cycles per misprediction. At 1 billion rows, that's billions of wasted cycles.

The XOR sign trick `(value ^ signed) - signed`:
- Positive (`signed=0`): `(value ^ 0) - 0 = value`
- Negative (`signed=-1`): `(value ^ -1) - (-1) = ~value + 1 = -value`

## Also important: store as int, not double

All 1BRC winners store temperatures as `int` (tenths of a degree). `12.3°C` → `123`. This:
- Avoids floating-point rounding issues
- Uses 4 bytes instead of 8
- Enables integer arithmetic (faster than FP)

## When to use

- Fixed-format numeric fields (prices in cents, temperatures, coordinates)
- High-throughput parsing (millions of records per second)
- When the number format is constrained and known at compile time

## References

- `CalculateAverage_thomaswue.java` lines 307-320 — convertIntoNumber
- `CalculateAverage_merykitty.java` lines 169-195 — parseDataPoint
- `CalculateAverage_jerrinot.java` lines 242-272 — parseAndStoreTemperature
- Example: `T03_IntegerParsing.java`
