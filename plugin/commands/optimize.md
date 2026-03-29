Analyze the Java or Kotlin code at the given path (or in the current conversation context) for data-intensive performance bottlenecks. Apply your knowledge of the optimization techniques below to identify concrete improvements.

Detect the language from the file extension (`.java` or `.kt`/`.kts`) and tailor all code examples to match.

## Your task

1. Read the target file(s): $ARGUMENTS
2. Profile the code mentally — identify hot loops, allocation patterns, I/O patterns, data structures, and parsing logic
3. For each bottleneck found, recommend one or more of these techniques with a specific, actionable code transformation:

### Technique checklist

| # | Technique | Look for (Java) | Look for (Kotlin) | Red flag |
|---|-----------|-----------------|-------------------|----------|
| 1 | Memory-Mapped I/O | `BufferedReader`, `Files.lines()`, `Scanner` | `.useLines {}`, `.readLines()`, `.bufferedReader()` | String-per-line on files >100MB |
| 2 | Parallel Processing | Single-threaded loops, `ConcurrentHashMap` in hot path | `.asSequence()` chains, `coroutineScope` on CPU-bound work, `ConcurrentHashMap` | Shared mutable state under contention |
| 3 | Branchless Parsing | `Double.parseDouble()`, `Integer.parseInt()` | `.toDouble()`, `.toInt()`, `String.format` in tight loops | Branch-heavy parsing on fixed-format data |
| 4 | Custom Hash Maps | `HashMap<String, ...>` in hot aggregation loops | `mutableMapOf()`, `HashMap`, `groupBy`/`groupingBy` in hot path | Millions of lookups with bounded key cardinality |
| 5 | Bulk Byte Scanning | Byte-by-byte delimiter scanning (`indexOf`, char loops) | `indexOf`, `split`, `Regex` in byte-level processing | Scanning for single bytes in large buffers |
| 6 | Off-Heap Memory | `new` in hot path, object allocation in tight loops | `data class` per record, `List<T>` of value objects in hot loops, autoboxing via `Int?`/`Long?` | Millions of small objects with short lifetimes |
| 7 | Benchmarking | `System.nanoTime()` without warmup, no DCE protection | `measureTimeMillis`/`measureNanoTime` without warmup or statistical treatment | Unreliable performance measurements |

## Output format

For each finding:
1. **Location**: file:line
2. **Bottleneck**: what's slow and why
3. **Technique**: which optimization applies
4. **Before/After**: show the concrete code change
5. **Expected impact**: rough speedup estimate (e.g., "2-5x for this hot path")

Prioritize findings by impact — the biggest wins first. If the code is already well-optimized, say so.

For reference implementations, see `src/main/java/com/example/T01-T08*.java` and `docs/*.md` in this repository.
