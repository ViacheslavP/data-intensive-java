# Data-Intensive Java — Claude Code Plugin

Low-level performance optimization skills for data-intensive Java and Kotlin applications, extracted from the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc).

All commands auto-detect the language from the file extension (`.java` or `.kt`/`.kts`) and emit code in the same language.

## Skills

This plugin provides slash commands that apply battle-tested optimization techniques to Java and Kotlin code:

| Command | Technique |
|---------|-----------|
| `/optimize` | Analyze code and recommend applicable optimizations |
| `/memory-mapped-io` | Replace BufferedReader/Files.lines with memory-mapped I/O |
| `/parallel` | Apply thread-per-core parallelism with work-stealing |
| `/branchless-parse` | Replace parsing with branchless bit manipulation |
| `/custom-hashmap` | Replace HashMap with open-addressing custom map |
| `/byte-scan` | Bulk byte scanning — SWAR (8B/op, any JDK) or Vector API (32-64B/op, JDK 21+) |
| `/off-heap` | Move hot data off-heap to eliminate GC pressure |
| `/benchmark` | Set up proper JMH/hyperfine benchmarking |

## Usage

Each command accepts a file path or code context as argument. Example:

```
/optimize src/main/java/com/example/DataProcessor.java
/byte-scan src/main/java/com/example/CsvParser.java
/benchmark src/main/kotlin/com/example/Pipeline.kt
```

## Requirements

- JDK 21+ (for Foreign Memory API, Vector API)
- Maven 3.8+

## Reference examples

Each technique has a runnable example in `src/main/java/com/example/` and documentation in `docs/`.
