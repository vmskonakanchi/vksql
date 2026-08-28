# Architecture

Detailed technical architecture of vkSQL — a distributed analytical query engine.

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              vkSQL Engine                                    │
│                                                                             │
│  ┌─────────┐   ┌──────────┐   ┌───────────┐   ┌───────────┐   ┌────────┐ │
│  │   SQL   │──▶│  Parser  │──▶│  Logical  │──▶│ Optimizer │──▶│Physical│ │
│  │  Input  │   │ (ANTLR4) │   │   Plan    │   │           │   │  Plan  │ │
│  └─────────┘   └──────────┘   └───────────┘   └───────────┘   └───┬────┘ │
│                                                                     │      │
│                                              ┌──────────────────────▼────┐ │
│                                              │    Execution Engine       │ │
│                                              │  ┌────────────────────┐  │ │
│                                              │  │ Vectorized Operators│  │ │
│                                              │  │ Scan │ Filter │ Join│  │ │
│                                              │  │ Agg  │Project│ Sort│  │ │
│                                              │  └─────────┬──────────┘  │ │
│                                              └────────────┼─────────────┘ │
│                                                           │               │
│  ┌────────────────────────────┐    ┌─────────────────────▼─────────────┐ │
│  │    Distributed Layer       │    │       Storage Engine               │ │
│  │  ┌─────────────────────┐  │    │  ┌─────────┐  ┌───────────────┐  │ │
│  │  │    Coordinator      │  │    │  │ Writer  │  │    Reader     │  │ │
│  │  │  ┌──────┐ ┌──────┐ │  │    │  │         │  │  (mmap-based) │  │ │
│  │  │  │Worker│ │Worker│ │  │    │  └─────────┘  └───────────────┘  │ │
│  │  │  │  1   │ │  N   │ │  │    │  ┌─────────────────────────────┐  │ │
│  │  │  └──────┘ └──────┘ │  │    │  │   Columnar File (.vkql)    │  │ │
│  │  └─────────────────────┘  │    │  └─────────────────────────────┘  │ │
│  └────────────────────────────┘    └───────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Data Flow

### Query Processing Pipeline

```
"SELECT sum(amount) FROM orders WHERE region = 'US'"
    │
    ▼
┌──────────────────────────────────────┐
│ 1. LEXER (ANTLR4)                    │
│    SQL string → token stream         │
│    [SELECT][sum][amount][FROM]...     │
└──────────────────┬───────────────────┘
                   ▼
┌──────────────────────────────────────┐
│ 2. PARSER (ANTLR4)                   │
│    token stream → parse tree (CST)   │
│    SelectStmt → SelectList → ...     │
└──────────────────┬───────────────────┘
                   ▼
┌──────────────────────────────────────┐
│ 3. AST BUILDER (Visitor)             │
│    parse tree → abstract syntax tree │
│    Query { projection, filter, ... } │
└──────────────────┬───────────────────┘
                   ▼
┌──────────────────────────────────────┐
│ 4. LOGICAL PLAN                      │
│    AST → relational algebra tree     │
│    Aggregate(Filter(Scan(orders)))   │
└──────────────────┬───────────────────┘
                   ▼
┌──────────────────────────────────────┐
│ 5. OPTIMIZER                         │
│    Predicate pushdown                │
│    Projection pruning                │
│    Join reordering                   │
└──────────────────┬───────────────────┘
                   ▼
┌──────────────────────────────────────┐
│ 6. PHYSICAL PLAN                     │
│    Choose operator implementations   │
│    HashAggregate(Scan(orders,        │
│      filter=region='US'))            │
└──────────────────┬───────────────────┘
                   ▼
┌──────────────────────────────────────┐
│ 7. EXECUTION                         │
│    Vectorized batch processing       │
│    RecordBatch (1024 rows) at a time │
│    → Result: sum = 4,521,300         │
└──────────────────────────────────────┘
```

## Storage Format

### File Layout

vkSQL uses a custom columnar file format (`.vkql`) optimized for analytical workloads:

```
┌───────────────────────────────────────────┐
│              File Header                   │
│  Magic: "VKQL" │ Version │ Schema Info    │
├───────────────────────────────────────────┤
│              Row Group 0                   │
│  ┌─────────────────────────────────────┐  │
│  │  Column 0: Page 0 │ Page 1 │ ...    │  │
│  │  Column 1: Page 0 │ Page 1 │ ...    │  │
│  │  Column 2: Page 0 │ Page 1 │ ...    │  │
│  └─────────────────────────────────────┘  │
├───────────────────────────────────────────┤
│              Row Group 1                   │
│  ┌─────────────────────────────────────┐  │
│  │  Column 0: Page 0 │ Page 1 │ ...    │  │
│  │  ...                                │  │
│  └─────────────────────────────────────┘  │
├───────────────────────────────────────────┤
│              ...                          │
├───────────────────────────────────────────┤
│              Footer                       │
│  ┌─────────────────────────────────────┐  │
│  │  Schema (column names + types)      │  │
│  │  Row Group metadata                 │  │
│  │    - offset, size, row count        │  │
│  │  Column metadata per row group      │  │
│  │    - page offsets                   │  │
│  │    - zone maps (min/max)            │  │
│  │    - null count                     │  │
│  │    - encoding type                  │  │
│  │    - compression type               │  │
│  │  Footer length (4 bytes)            │  │
│  │  Magic: "VKQL" (4 bytes)           │  │
│  └─────────────────────────────────────┘  │
└───────────────────────────────────────────┘
```

### Page Structure

Each page contains a fixed number of values for a single column:

```
┌──────────────────────────────────┐
│         Page Header              │
│  Encoding │ Compressed Size │    │
│  Uncompressed Size │ Null Count │
├──────────────────────────────────┤
│         Null Bitmap              │
│  1 bit per row (0=null, 1=value) │
├──────────────────────────────────┤
│         Encoded Data             │
│  (Dictionary / RLE / Delta /     │
│   Plain encoded values)          │
├──────────────────────────────────┤
│  Optional: Compressed with       │
│  Snappy or Zstd                  │
└──────────────────────────────────┘
```

### Encoding Schemes

| Encoding | Best For | How It Works |
|----------|----------|--------------|
| **Plain** | High-cardinality data | Raw values stored sequentially |
| **Dictionary** | Low-cardinality strings | Dictionary + integer indices |
| **RLE** | Repeated values, sorted columns | Run-length: (value, count) pairs |
| **Delta** | Timestamps, sequential IDs | Store deltas between consecutive values |

### Zone Maps (Min/Max Statistics)

Every page stores min/max values for the column data it contains. During query execution, the engine checks these statistics before reading a page — if the filter predicate cannot match any value in the range `[min, max]`, the entire page is skipped.

```
Query: WHERE amount > 1000

Page 0: min=50, max=800    → SKIP (max < 1000)
Page 1: min=200, max=1500  → READ (range overlaps)
Page 2: min=1100, max=9000 → READ (range overlaps)
Page 3: min=10, max=100    → SKIP (max < 1000)
```

## Execution Engine

### Volcano vs Vectorized

Traditional databases use the **Volcano model** (iterator-based, one row at a time). vkSQL uses a **vectorized model** that processes data in batches:

| Aspect | Volcano | Vectorized (vkSQL) |
|--------|---------|-------------------|
| Unit of work | 1 row | 1024+ rows (RecordBatch) |
| Function calls | O(rows × operators) | O(batches × operators) |
| CPU cache | Poor (pointer chasing) | Excellent (sequential access) |
| SIMD potential | None | High (tight loops over arrays) |
| Branch prediction | Poor | Good (batched conditionals) |

### Operator Model

All operators implement a pull-based interface:

```java
public interface PhysicalOperator {
    RecordBatch next();  // Pull next batch of rows
    void open();         // Initialize operator state
    void close();        // Release resources
}
```

**Operator tree example:**

```
HashAggregate [sum(amount), group by region]
    │
    ▼
  Filter [amount > 100]
    │
    ▼
  TableScan [orders] (mmap + zone map pruning)
```

### RecordBatch

The fundamental data unit in the execution engine:

```
RecordBatch (1024 rows):
┌─────────────────────────────────────────┐
│  Column Vector: "region" (String)       │
│  [US, US, EU, EU, US, JP, ...]          │
├─────────────────────────────────────────┤
│  Column Vector: "amount" (Long)         │
│  [500, 1200, 300, 800, 2000, ...]       │
├─────────────────────────────────────────┤
│  Selection Vector (optional)            │
│  [0, 1, 4, ...] (indices of valid rows) │
├─────────────────────────────────────────┤
│  Row Count: 1024                        │
└─────────────────────────────────────────┘
```

### Key Operators

- **TableScan** — reads columnar pages via mmap, applies zone map pruning, decodes/decompresses
- **Filter** — evaluates predicates on column vectors, produces selection vector
- **Project** — evaluates expressions, produces new column vectors
- **HashAggregate** — builds hash table on group keys, computes aggregate functions
- **HashJoin** — builds hash table on build side, probes with probe side
- **Sort** — external sort with spill-to-disk for large datasets

## Distributed Execution

### Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Coordinator                        │
│  ┌───────────────────────────────────────────────┐  │
│  │  Query Planning + Fragment Distribution        │  │
│  │  Partition Assignment + Result Aggregation     │  │
│  └───────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────┘
                         │ gRPC
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Worker 1   │ │   Worker 2   │ │   Worker N   │
│ ┌──────────┐ │ │ ┌──────────┐ │ │ ┌──────────┐ │
│ │ Executor │ │ │ │ Executor │ │ │ │ Executor │ │
│ │ (local)  │ │ │ │ (local)  │ │ │ │ (local)  │ │
│ └──────────┘ │ │ └──────────┘ │ │ └──────────┘ │
│ ┌──────────┐ │ │ ┌──────────┐ │ │ ┌──────────┐ │
│ │ Storage  │ │ │ │ Storage  │ │ │ │ Storage  │ │
│ │ (local)  │ │ │ │ (local)  │ │ │ │ (local)  │ │
│ └──────────┘ │ │ └──────────┘ │ │ └──────────┘ │
└──────────────┘ └──────────────┘ └──────────────┘
```

### Query Execution Flow (Distributed)

1. **Parse & Plan** — Coordinator parses SQL, generates physical plan
2. **Fragment** — Plan is split into fragments based on data partitioning
3. **Assign** — Fragments assigned to workers based on data locality
4. **Execute** — Workers execute fragments in parallel on local data
5. **Shuffle** — Intermediate results shuffled between workers (hash-partitioned)
6. **Aggregate** — Coordinator collects partial results, produces final output

### Partitioning & Shuffle

Data is hash-partitioned across workers by a partition key. For distributed joins:

```
                    Shuffle (hash on join key)
Worker 1 ─────────────────────────────────────▶ Worker 1
    │ partition(key) = 0                           │ receives all key=0
    │                                              │
Worker 2 ─────────────────────────────────────▶ Worker 2
    │ partition(key) = 1                           │ receives all key=1
    │                                              │
Worker 3 ─────────────────────────────────────▶ Worker 3
      partition(key) = 2                             receives all key=2
```

### gRPC Service Definition

```protobuf
service ShuffleService {
    rpc ExecuteFragment(FragmentRequest) returns (stream RecordBatchResponse);
    rpc SendBatch(stream RecordBatchRequest) returns (AckResponse);
    rpc Heartbeat(HeartbeatRequest) returns (HeartbeatResponse);
}
```

### Fault Tolerance

- **Heartbeat monitoring** — Coordinator detects worker failures via periodic heartbeats
- **Fragment reassignment** — Failed fragments are reassigned to healthy workers
- **Retry with backoff** — Transient failures retried with exponential backoff
- **Partial result recovery** — Completed fragments are cached, only failed work is recomputed

## Performance Optimizations

### Memory-Mapped I/O

- Files are mapped into virtual memory via `MemorySegment` (Panama API)
- OS page cache handles buffering — no explicit buffer management
- Zero-copy: data is read directly from mmap'd region, no intermediate copies
- Columnar layout ensures sequential access patterns → excellent prefetching

### Vectorized Loops

Processing data in tight loops over primitive arrays enables:
- CPU cache line utilization (sequential access)
- Auto-vectorization by JIT compiler (SIMD)
- Minimal virtual dispatch overhead
- Branch-free evaluation where possible

```java
// Vectorized filter: amount > threshold
for (int i = 0; i < batch.rowCount(); i++) {
    selectionVector[i] = amounts[i] > threshold ? 1 : 0;
}
```

### Parallel Execution

- **Virtual threads** — lightweight concurrency for I/O-bound operations
- **Parallel scan** — multiple row groups scanned concurrently
- **Partitioned aggregation** — thread-local hash tables merged at the end
- **Work stealing** — idle threads pick up remaining work units

### JVM Tuning

```bash
# Recommended JVM flags
-XX:+UseZGC                    # Low-latency GC
-XX:+ZGenerational             # Generational ZGC (Java 21+)
-Xmx16g                       # Heap size for large datasets
--enable-preview               # Panama MemorySegment, virtual threads
--add-modules jdk.incubator.vector  # Vector API (experimental)
```

### Predicate Pushdown

Three levels of pushdown:

1. **File level** — skip entire files based on partition metadata
2. **Row group level** — skip row groups based on zone maps
3. **Page level** — skip individual pages based on page-level min/max

This avoids I/O entirely for data that cannot match the predicate.
