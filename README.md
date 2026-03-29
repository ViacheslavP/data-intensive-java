# Data-Intensive Java

Claude Code plugin with low-level performance optimization skills for Java and Kotlin, extracted from the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc).

## Install

```
/plugin install github:ViacheslavP/data-intensive-java
```

## Skills

| Command | Technique |
|---------|-----------|
| `/optimize` | Analyze code and recommend optimizations |
| `/memory-mapped-io` | Zero-copy file I/O via mmap |
| `/parallel` | Thread-per-core with work-stealing |
| `/branchless-parse` | Branchless bit-manipulation parsing |
| `/custom-hashmap` | Open-addressing map with inline keys |
| `/byte-scan` | SWAR (8B/op) or Vector API SIMD (32-64B/op) |
| `/off-heap` | Foreign Memory API, zero-GC patterns |
| `/benchmark` | JMH / hyperfine / kotlinx-benchmark setup |

Pass a file path as argument: `/optimize src/main/java/com/example/Processor.java`

## Disclaimer

This skillset was generated using an LLM (Claude). While the techniques are based on real 1BRC solutions, verify critical optimizations against the original sources before using in production.

## Attribution

Techniques derived from the [1BRC project](https://github.com/gunnarmorling/1brc) by Gunnar Morling and contributors (Apache 2.0).
