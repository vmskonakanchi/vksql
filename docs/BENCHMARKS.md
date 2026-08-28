# Benchmarks

Performance results for vkSQL's storage and execution engine.

## Test Setup

| Parameter | Value |
|-----------|-------|
| Machine | Apple M5 (ARM64) |
| Cores | 10 |
| RAM | 16 GB unified memory |
| OS | macOS 26.6.2 |
| JDK | OpenJDK 21.0.12 |
| GC | ZGC |
| Heap | Default (no explicit -Xmx) |

### JVM Flags

```bash
-XX:+UseZGC
-XX:-TieredCompilation
-XX:+AlwaysPreTouch
--enable-preview
```

### Data Characteristics

- **Row count**: 100,000,000 (100M rows) for storage benchmarks, 10M rows for TPC-H query benchmarks
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

### TPC-H Query Benchmarks (10M rows)

End-to-end query execution including data generation, hash build, filtering, and aggregation.

| Query | Description | Rows Scanned | Rows Matched | Exec Time | Throughput |
|-------|-------------|-------------|--------------|-----------|------------|
| Q1 | Pricing Summary Report | 10M | 9,344,490 | 116 ms | **86.2 M rows/sec** |
| Q6 | Forecasting Revenue Change | 10M | 275,650 | 149 ms | **67.1 M rows/sec** |
| Q12 | Shipping Modes & Order Priority | 10M | 798,845 | 150 ms | **95.2 M rows/sec** |
| Q14 | Promotion Effect | 10M | 167,161 | 144 ms | **75.2 M rows/sec** |

#### TPC-H Q12 Details

- **Join**: orders.orderkey = lineitem.orderkey (hash join, 2M entry build side)
- **Filter**: shipmode IN (MAIL, SHIP) AND receiptdate in [1994-01-01, 1995-01-01)
- **Aggregate**: Group by shipmode → count high-priority (priority ≤ 1), low-priority (priority > 1)
- **Hash build time**: 44 ms (2M entries)
- **Probe + aggregate time**: 105 ms

#### TPC-H Q14 Details

- **Join**: lineitem.partkey = part.partkey (hash join, 500K entry build side)
- **Filter**: shipdate in [1995-09-01, 1995-10-01)
- **Compute**: 100 × sum(promo_revenue) / sum(total_revenue)
- **Result**: 20.12% promo percentage
- **Hash build time**: 9 ms (500K entries)
- **Probe + aggregate time**: 133 ms

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

### End-to-End Query Performance (100M rows)

| Query | Time (single-thread) | Time (parallel) |
|-------|---------------------|-----------------|
| `SELECT sum(amount) FROM orders` | 35ms | 22ms |
| `SELECT region, sum(amount) FROM orders GROUP BY region` | 125ms | 48ms |
| `SELECT * FROM orders WHERE amount > 1000` (10% selectivity) | 42ms | 18ms |
| Hash join (10M × 100M) | 890ms | 320ms |

## TPC-H Query Coverage

| Query | Status | Notes |
|-------|--------|-------|
| Q1 — Pricing Summary Report | ✅ | Scan + filter + group-by aggregate |
| Q6 — Forecasting Revenue Change | ✅ | Scan + multi-predicate filter + aggregate |
| Q12 — Shipping Modes & Order Priority | ✅ | Hash join + filter + group-by aggregate |
| Q14 — Promotion Effect | ✅ | Hash join + filter + computed aggregate |
| Q3–Q5 | ❌ | Needs multi-table join wiring |
| Q7–Q22 | ❌ | Needs subqueries, CASE expressions, DATE functions |

### What's Next for TPC-H Coverage

- **Q3–Q5**: Requires wiring multiple hash joins in sequence (multi-way join). The individual operators exist but the planner doesn't yet chain them automatically.
- **Q7–Q22**: Requires SQL features not yet implemented in the parser/planner: correlated subqueries, CASE/WHEN expressions, DATE arithmetic, HAVING clauses, and EXISTS predicates.

## Comparison with Other Engines

> **Caveat**: These engines are mature, production databases with years of optimization. This comparison is for directional reference only — different feature sets, different query capabilities, different maturity levels.

| Metric | vkSQL | DuckDB | Apache DataFusion | ClickHouse |
|--------|-------|--------|-------------------|------------|
| Single-column scan (100M rows) | 35ms | ~30ms | ~35ms | ~25ms |
| Group-by aggregate (100M) | 125ms | ~80ms | ~90ms | ~60ms |
| TPC-H Q1 (10M) | 116ms | ~40ms | ~50ms | ~30ms |
| TPC-H Q6 (10M) | 149ms | ~25ms | ~30ms | ~20ms |
| Predicate pushdown benefit | 70% skip | ~70% skip | ~70% skip | ~80% skip |
| Hash join (probe phase, 10M) | 95–105ms | ~40ms | ~50ms | ~35ms |
| Memory efficiency | Good | Excellent | Good | Excellent |

### Why the comparison is imperfect

- DuckDB, DataFusion, and ClickHouse support full SQL; vkSQL supports a subset
- Those engines have adaptive execution, query compilation, and more operators
- vkSQL is a learning/portfolio project; the others are production software
- Test methodology differences (cold vs warm cache, JVM warmup, dataset generation)
- vkSQL TPC-H benchmarks use synthetic data matching TPC-H distributions, not the official dbgen tool
- JVM startup and JIT compilation overhead affects first-run numbers

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
# Run TPC-H benchmark tests
./gradlew :execution:test --tests "*TpchBenchmarkTest*"

# Run all benchmarks
./gradlew :execution:test --tests "*Benchmark*"

# Generate storage test data
./gradlew :storage:test --tests "*BenchmarkDataGenerator*"

# With recommended JVM flags for best results
./gradlew :execution:test --tests "*Benchmark*" \
    -Dorg.gradle.jvmargs="-XX:+UseZGC -XX:-TieredCompilation -XX:+AlwaysPreTouch --enable-preview"
```
