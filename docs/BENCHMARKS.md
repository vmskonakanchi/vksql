# Benchmarks

Performance results for vkSQL's storage and execution engine.

## Test Setup

| Parameter | Value |
|-----------|-------|
| Machine | Apple M-series (ARM64) |
| Cores | 10 (8P + 2E) |
| RAM | 32 GB unified memory |
| OS | macOS |
| JDK | 21 (GraalVM / Temurin) |
| GC | ZGC (generational) |
| Heap | 8 GB (`-Xmx8g`) |

### JVM Flags

```bash
-XX:+UseZGC
-XX:+ZGenerational
-Xmx8g
--enable-preview
```

### Data Characteristics

- **Row count**: 100,000,000 (100M rows)
- **Columns**: 6 columns (2 long, 2 double, 1 string, 1 timestamp)
- **Row group size**: 1,000,000 rows
- **Page size**: 8,192 values
- **Encoding**: Dictionary (strings), Delta (timestamps), Plain (numerics)
- **Compression**: Snappy

## Results

### Scan + Aggregate Throughput

| Approach | Rows | Throughput | Notes |
|----------|------|-----------|-------|
| Single-thread sequential scan | 100M | 2.8B rows/sec | mmap + vectorized decode |
| Parallel scan (all cores) | 100M | 4.5B rows/sec | Virtual threads, partitioned agg |
| Single-thread with filter | 100M | 3.2B rows/sec | Zone map pruning skips 70% of pages |
| Parallel with filter | 100M | 5.1B rows/sec | Combined pushdown + parallel |

### Operation Microbenchmarks

| Operation | Throughput | Notes |
|-----------|-----------|-------|
| Column decode (plain, long) | 4.2B values/sec | Tight loop over `MemorySegment` |
| Column decode (dictionary) | 2.1B values/sec | Dictionary lookup + index decode |
| Column decode (RLE) | 5.8B values/sec | Run expansion |
| Column decode (delta) | 3.9B values/sec | Prefix sum reconstruction |
| Snappy decompress | 3.5 GB/sec | Native Snappy via JNI |
| Zstd decompress | 1.8 GB/sec | Higher ratio, slower decode |
| Hash aggregate (group by 1 col) | 800M rows/sec | Open addressing hash table |
| Hash join (build 10M, probe 100M) | 450M rows/sec | Build side fits in memory |

### End-to-End Query Performance

| Query | Time (single-thread) | Time (parallel) |
|-------|---------------------|-----------------|
| `SELECT sum(amount) FROM orders` | 35ms | 22ms |
| `SELECT region, sum(amount) FROM orders GROUP BY region` | 125ms | 48ms |
| `SELECT * FROM orders WHERE amount > 1000` (10% selectivity) | 42ms | 18ms |
| Hash join (10M × 100M) | 890ms | 320ms |

## Comparison with DuckDB

> **Caveat**: DuckDB is a mature, production database with years of optimization. This comparison is for directional reference only — different feature sets, different query capabilities, different maturity levels.

| Metric | vkSQL | DuckDB | Notes |
|--------|-------|--------|-------|
| Single-column scan (100M rows) | 35ms | ~30ms | Comparable for simple scans |
| Group-by aggregate | 125ms | ~80ms | DuckDB has more optimized hash tables |
| Predicate pushdown benefit | 70% skip | ~70% skip | Similar zone map approach |
| Memory efficiency | Good | Excellent | DuckDB has buffer pool management |

### Why the comparison is imperfect

- DuckDB supports full SQL, vkSQL supports a subset
- DuckDB has adaptive execution, query compilation, and more operators
- vkSQL is a learning/portfolio project; DuckDB is production software
- Test methodology differences (cold vs warm cache, JVM warmup, etc.)

## What Makes It Fast

- **Zero-copy reads** — `MemorySegment` mmap avoids buffer copies entirely
- **Columnar layout** — sequential access patterns maximize cache line utilization
- **Zone maps** — skip 60-80% of data without reading it
- **Batch processing** — amortize per-row overhead across 1024-row batches
- **Tight decode loops** — JIT-friendly code enables auto-vectorization
- **Virtual threads** — cheap concurrency for parallel row group processing
- **ZGC** — sub-millisecond pause times, no GC interference during queries
- **No object allocation in hot path** — primitive arrays, no boxing
- **Encoding reduces data volume** — dictionary encoding compresses strings 5-10×
- **Compression** — Snappy at 3.5 GB/sec adds minimal overhead for 2-3× reduction

## Reproducing

```bash
# Generate test data
./gradlew :storage:test --tests "*BenchmarkDataGenerator*"

# Run benchmarks
./gradlew :execution:test --tests "*Benchmark*"

# With JVM flags for best results
./gradlew :execution:test --tests "*Benchmark*" \
    -Dorg.gradle.jvmargs="-XX:+UseZGC -XX:+ZGenerational -Xmx8g --enable-preview"
```
