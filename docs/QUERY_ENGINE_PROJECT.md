# vkSQL — Distributed Analytical Query Engine

## Vision

A columnar analytical query engine that can execute SQL queries over large datasets distributed across multiple nodes. Think: a mini Trino/DataFusion/ClickHouse.

This project builds on your existing systems experience (compiler in C, stack VM in Go) and pushes into the domain that Databricks, Snowflake, ClickHouse, Google, Uber, and LinkedIn care most about: **how do you efficiently store, plan, and execute analytical queries over billions of rows across a cluster?**

By the end, you'll have a working distributed SQL engine — not a toy, but something that runs TPC-H queries across multiple nodes with vectorized execution and fault tolerance.

**Repository:** `github.com/vmskonakanchi/vksql`

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT (SQL)                                        │
│                          "SELECT sum(price) FROM orders WHERE date > '2024-01'"  │
└─────────────────────────────┬───────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           COORDINATOR NODE                                       │
│                                                                                  │
│  ┌──────────┐   ┌──────────────┐   ┌───────────┐   ┌──────────────┐            │
│  │  SQL     │──▶│  Logical     │──▶│ Optimizer │──▶│  Physical    │            │
│  │  Parser  │   │  Plan        │   │ (Rules)   │   │  Plan        │            │
│  └──────────┘   └──────────────┘   └───────────┘   └──────┬───────┘            │
│                                                            │                     │
│  ┌──────────────┐                    ┌─────────────────────▼──────────────────┐ │
│  │   Catalog    │◀──────────────────▶│  Distributed Planner / Scheduler      │ │
│  │  (Metadata)  │                    │  (Split into stages, assign to nodes)  │ │
│  └──────────────┘                    └──────────┬────────────┬────────────────┘ │
└─────────────────────────────────────────────────┼────────────┼──────────────────┘
                                                  │            │
                          ┌───────────────────────┘            └────────────────┐
                          ▼                                                     ▼
┌─────────────────────────────────────────┐   ┌─────────────────────────────────────────┐
│            WORKER NODE 1                 │   │            WORKER NODE 2                 │
│                                          │   │                                          │
│  ┌────────────────────────────────────┐  │   │  ┌────────────────────────────────────┐  │
│  │     Vectorized Execution Engine    │  │   │  │     Vectorized Execution Engine    │  │
│  │                                    │  │   │  │                                    │  │
│  │  ┌────────┐  ┌──────┐  ┌───────┐  │  │   │  │  ┌────────┐  ┌──────┐  ┌───────┐  │  │
│  │  │  Scan  │─▶│Filter│─▶│HashAgg│  │  │   │  │  │  Scan  │─▶│Filter│─▶│HashAgg│  │  │
│  │  └────────┘  └──────┘  └───────┘  │  │   │  │  └────────┘  └──────┘  └───────┘  │  │
│  └────────────────────────────────────┘  │   │  └────────────────────────────────────┘  │
│                                          │   │                                          │
│  ┌────────────────────────────────────┐  │   │  ┌────────────────────────────────────┐  │
│  │       Columnar Storage Layer       │  │   │  │       Columnar Storage Layer       │  │
│  │                                    │  │   │  │                                    │  │
│  │  ┌─────────┐ ┌─────────┐          │  │   │  │  ┌─────────┐ ┌─────────┐          │  │
│  │  │RowGroup │ │RowGroup │  ...     │  │   │  │  │RowGroup │ │RowGroup │  ...     │  │
│  │  │  0..3   │ │  4..7   │          │  │   │  │  │  8..11  │ │ 12..15  │          │  │
│  │  └─────────┘ └─────────┘          │  │   │  │  └─────────┘ └─────────┘          │  │
│  └────────────────────────────────────┘  │   │  └────────────────────────────────────┘  │
└─────────────────────────────────────────┘   └─────────────────────────────────────────┘
                          │                                                     │
                          └──────────── gRPC Shuffle (Exchange) ────────────────┘
```

### Data Flow

```
SQL String
    │
    ▼
┌─────────────────────┐
│   Lexer / Parser    │  (ANTLR4 or JSqlParser)
│   → AST             │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│   Logical Plan      │  Tree of RelNodes:
│   (Scan, Filter,    │  Scan → Filter → Project → Aggregate → Sort
│    Project, Join,   │
│    Aggregate, Sort) │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│   Optimizer         │  Rules:
│   (Rule-based)      │  • Predicate pushdown
│                     │  • Projection pushdown
│                     │  • Filter simplification
│                     │  • Join reordering (later)
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│   Physical Plan     │  Concrete operators:
│                     │  • TableScan (with pushed predicates)
│                     │  • HashJoin / SortMergeJoin
│                     │  • HashAggregate
│                     │  • Exchange (shuffle)
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│   Executor          │  Vectorized, batch-at-a-time
│   (1024-row batches │  Column vectors as primitive arrays
│    in tight loops)  │  JIT auto-vectorization friendly
└─────────────────────┘
```

---

## Phase 1: Columnar Storage Engine (Weeks 1–4)

**Goal:** Read/write data in a custom columnar format with predicate pushdown.

### File Format Design

```
┌─────────────────────────────────────────────────┐
│                  FILE LAYOUT                      │
├─────────────────────────────────────────────────┤
│  Magic Number (4 bytes): "VKQL"                  │
├─────────────────────────────────────────────────┤
│  Row Group 0                                     │
│  ┌───────────────────────────────────────────┐  │
│  │  Column Chunk: col_0 (INT)                │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  Page 0: [values...] (compressed)   │  │  │
│  │  │  Page 1: [values...] (compressed)   │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  │  Column Chunk: col_1 (STRING)             │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  Page 0: [offsets][data](compressed)│  │  │
│  │  └─────────────────────────────────────┘  │  │
│  │  ...                                      │  │
│  └───────────────────────────────────────────┘  │
├─────────────────────────────────────────────────┤
│  Row Group 1                                     │
│  ...                                             │
├─────────────────────────────────────────────────┤
│  FOOTER                                          │
│  ┌───────────────────────────────────────────┐  │
│  │  Schema (column names, types)             │  │
│  │  Row group metadata:                      │  │
│  │    • Offset, size                         │  │
│  │    • Row count                            │  │
│  │    • Per-column stats: min, max, null_cnt │  │
│  │  Footer length (4 bytes)                  │  │
│  └───────────────────────────────────────────┘  │
├─────────────────────────────────────────────────┤
│  Magic Number (4 bytes): "VKQL"                  │
└─────────────────────────────────────────────────┘
```

### Milestones

**Week 1:** Define file format (row groups, column chunks, metadata footer). Implement writing columns with fixed-width types (INT, LONG, DOUBLE).
- Design the binary layout. Write a `ColumnWriter` that buffers rows into pages (target: 64KB per page, 1M rows per row group).
- Write a `FileWriter` that assembles column chunks into row groups and appends the footer.
- Deliverable: Can write a file with 10M rows of `(int, long, double)` and read back the footer metadata.

**Week 2:** Add variable-length types (STRING with offset/length encoding). Add null bitmaps.
- STRING encoding: `[num_values][offset_0][offset_1]...[offset_n][byte_data...]`
- Null bitmap: 1 bit per value, packed into byte arrays. Validity = 1 means non-null.
- Deliverable: Can write mixed-type tables with nulls and read them back correctly.

**Week 3:** Implement encoding schemes — dictionary encoding, run-length encoding, delta encoding. Add snappy/zstd compression per page.
- Dictionary encoding: For low-cardinality STRING columns. Store dictionary + indices.
- RLE: For sorted/repetitive INT columns. Store (value, count) pairs.
- Delta encoding: For monotonically increasing values (timestamps, IDs).
- Compression: Apply after encoding. Snappy for speed, Zstd for ratio. Configurable per column.
- Deliverable: Same 10M row file is now 3-5x smaller. Benchmark encoding/decoding speed.

**Week 4:** Implement reader with predicate pushdown using min/max statistics per row group. Benchmark: read speed, compression ratios.
- `ColumnReader` decodes pages, applies decompression + decoding.
- `FileReader` reads footer first, then selectively reads row groups based on predicates vs. min/max stats.
- Zone map filtering: If predicate is `WHERE id > 5000` and row group max(id) = 4999, skip it entirely.
- Deliverable: Benchmark showing X GB/s read throughput, Y% row groups skipped on selective queries.

### Key Concepts to Learn
- Column-oriented storage vs. row-oriented (why analytics prefers columnar)
- Encoding schemes: dictionary, RLE, delta, bit-packing
- I/O amplification: reading less data from disk
- Cache-friendly access patterns: sequential memory access, SIMD-friendly layouts
- Zone maps / min-max indexes

### What to Read
- Chapter 3 (Storage and Retrieval) in *Designing Data-Intensive Applications* by Martin Kleppmann
- [Apache Parquet format specification](https://parquet.apache.org/documentation/latest/)
- [DuckDB blog: "The Storage Layer"](https://duckdb.org/2022/10/28/lightweight-compression.html)
- [CStore paper (Stonebraker et al.)](https://vldb.org/conf/2005/papers/p553-stonebraker.pdf)
- [The Design and Implementation of Modern Column-Oriented Database Systems](https://stratos.seas.harvard.edu/files/stratos/files/columnstoresfntdbs.pdf)

### Interview Talking Point
> "I built a columnar storage engine that achieves 4-6x compression ratio with dictionary encoding for string columns and delta encoding for timestamps. The zone-map based predicate pushdown skips 70-90% of row groups on selective queries, reducing I/O by an order of magnitude."

---

## Phase 2: Query Execution Engine (Weeks 5–9)

**Goal:** Execute SQL queries (SELECT, WHERE, JOIN, GROUP BY, ORDER BY) over your columnar files using vectorized execution.

### Milestones

**Week 5:** SQL parsing. Use a parser library (JSqlParser or ANTLR grammar). Produce an AST. Convert AST to logical plan.
- Logical plan is a tree of `RelNode`:
  ```
  Aggregate(sum(price), groupBy=nation)
    └── Filter(date > '2024-01-01')
          └── Join(orders.custkey = customer.custkey)
                ├── Scan(orders)
                └── Scan(customer)
  ```
- Define the `RelNode` interface hierarchy: `ScanNode`, `FilterNode`, `ProjectNode`, `JoinNode`, `AggregateNode`, `SortNode`, `LimitNode`.
- Implement `SqlToRelConverter` that walks the AST and produces a logical plan tree.
- Deliverable: Parse TPC-H Q1 SQL string → logical plan tree → print it.

**Week 6:** Logical plan optimizer. Implement rules using the visitor pattern.
- **Predicate pushdown:** Move filters below joins (push filters to the scan that owns the referenced columns).
- **Projection pushdown:** Only read columns that are needed by upstream operators.
- **Filter simplification:** `x > 5 AND x > 3` → `x > 5`. `true AND p` → `p`.
- **Constant folding:** `1 + 2` → `3` at plan time.
- Implement as a list of `Rule` objects, each with `matches(node)` and `apply(node)` methods. Apply rules in fixed-point iteration until no rule fires.
- Deliverable: Show before/after plans for TPC-H queries demonstrating optimization.

**Week 7:** Physical plan. Map logical operators to physical operators. Implement Volcano-style pull-based execution.
- Each physical operator implements:
  ```java
  interface PhysicalOperator {
      void open();          // Initialize state
      RecordBatch next();   // Return next batch (null = done)
      void close();         // Release resources
  }
  ```
- Physical operators: `TableScanOp`, `FilterOp`, `ProjectOp`, `HashJoinOp`, `HashAggregateOp`, `SortOp`, `LimitOp`.
- `RecordBatch`: a batch of N rows represented as column vectors.
- Deliverable: Execute `SELECT * FROM orders WHERE price > 100` end-to-end and get correct results.

**Week 8:** Vectorized execution. Switch from row-at-a-time to batch-at-a-time (1024 rows per batch).
- `ColumnVector`: wraps a primitive array (`int[]`, `long[]`, `double[]`) + null bitmap + selection vector.
- Selection vectors: Instead of materializing filtered rows, pass a `int[] selection` of valid indices.
- Expression evaluation: Compile expressions into tight loops over arrays:
  ```java
  // Vectorized: price > 100.0
  for (int i = 0; i < batch.size; i++) {
      result[i] = prices[i] > 100.0 ? 1 : 0;
  }
  ```
- These loops are JIT-friendly: no virtual dispatch, predictable branches, sequential memory access.
- Deliverable: Benchmark Volcano (row-at-a-time) vs. vectorized (batch) on a full table scan with filter. Expect 3-5x speedup.

**Week 9:** Hash join, hash aggregate, sort-merge join. Benchmark TPC-H queries 1, 6, 12 on ~1GB data.
- **Hash Join:**
  - Build phase: Hash the smaller table (build side) into a hash table keyed on join column.
  - Probe phase: For each row of the larger table (probe side), look up in the hash table.
  - Use open addressing with linear probing for cache efficiency.
- **Hash Aggregate:**
  - Hash on group-by keys. Each bucket holds partial aggregate state (count, sum, min, max).
  - Flush when done.
- **Sort-Merge Join:**
  - Sort both inputs on join key. Merge with two pointers.
  - Better for already-sorted data or when hash table doesn't fit in memory.
- Deliverable: TPC-H Q1 at X million rows/sec. Compare with DuckDB single-threaded as a reference point.

### Key Concepts
- Query planning: converting declarative SQL to an imperative execution plan
- Rule-based optimization vs. cost-based optimization (implement rule-based first)
- Volcano/iterator model: simple, composable, but slow (virtual dispatch per row)
- Vectorized execution: amortize interpretation overhead across batches
- Hash tables for joins: open addressing, Robin Hood hashing, pre-filtering with Bloom filters

### What to Read
- [CMU 15-721: Advanced Database Systems](https://15721.courses.cs.cmu.edu/spring2024/) — Andy Pavlo's lectures
- [MonetDB/X100: Hyper-Pipelining Query Execution](https://www.cidrdb.org/cidr2005/papers/P19.pdf)
- [Morsel-Driven Parallelism (HyPer)](https://db.in.tum.de/~leis/papers/morsels.pdf)
- [How Query Engines Work](https://howqueryengineswork.com/) — Andy Grove's book (free)
- [Apache Calcite](https://calcite.apache.org/) — study its optimizer architecture for inspiration

### Interview Talking Point
> "I implemented vectorized execution which processes 1024-row batches in tight loops, achieving X million rows/sec for TPC-H Q1 on a single node — 5x faster than my initial Volcano iterator model. The key insight is eliminating per-row virtual dispatch and keeping data in cache-friendly columnar arrays."

---

## Phase 3: Distributed Execution (Weeks 10–14)

**Goal:** Execute queries across multiple worker nodes with data partitioned by hash/range.

### Milestones

**Week 10:** Data partitioning. Hash-partition your columnar files across N nodes. Write a partition manager.
- Hash partitioning: `partition_id = hash(partition_key) % num_partitions`
- Each partition is a directory of columnar files on one node.
- `PartitionManager`: Given a table and partition key, knows which files live on which node.
- Write a `DataLoader` that takes a CSV/columnar file and distributes it across N local directories (simulating N nodes).
- Deliverable: TPC-H `orders` table split across 4 partitions by `orderkey`. Each partition readable independently.

**Week 11:** Distributed query planning. Split query into stages with Exchange operators.
- A query becomes a DAG of stages:
  ```
  Stage 0 (all workers): Scan + Filter + Partial Aggregate
         │
         ▼ (Shuffle by group-by key)
  Stage 1 (all workers): Final Aggregate
         │
         ▼ (Gather to coordinator)
  Stage 2 (coordinator): Sort + Limit + Return to client
  ```
- `Exchange` operator: marks a boundary between stages. Defines repartitioning strategy (hash, broadcast, gather).
- Implement `DistributedPlanner` that inserts Exchange operators into the physical plan and splits into stages.
- Deliverable: Given TPC-H Q1, produce a multi-stage plan with correct Exchange operators.

**Week 12:** Shuffle implementation. Workers send intermediate results to other workers via gRPC streaming.
- Define protobuf messages for `RecordBatch` serialization:
  ```protobuf
  message ColumnData {
      DataType type = 1;
      bytes values = 2;       // raw column data
      bytes null_bitmap = 3;
  }
  message RecordBatch {
      repeated ColumnData columns = 1;
      int32 num_rows = 2;
  }
  ```
- gRPC service: `ShuffleService.PushBatch(stream RecordBatch)` — workers push batches to downstream workers.
- Hash-based repartitioning: For each batch, compute target partition for each row, split batch, send to appropriate worker.
- Back-pressure: Use gRPC flow control to avoid overwhelming receivers.
- Deliverable: Two workers can shuffle data between each other. Verify correctness (all rows arrive, no duplicates).

**Week 13:** Coordinator. Receives SQL, plans, distributes stages to workers, collects results.
- Coordinator responsibilities:
  1. Parse SQL, optimize, produce distributed physical plan
  2. Assign stages to workers (initially: all workers run all stages that touch their data)
  3. Send stage plans to workers via gRPC: `WorkerService.ExecuteStage(StagePlan)`
  4. Collect final results from the last stage
  5. Return results to client
- Worker responsibilities:
  1. Receive a `StagePlan` (physical plan fragment + input sources)
  2. Execute it locally using the vectorized engine
  3. Push output to next stage's workers (shuffle) or back to coordinator (gather)
- Deliverable: End-to-end query execution: client → coordinator → workers → shuffle → coordinator → client.

**Week 14:** Fault tolerance. Task-level retry on failure. Heartbeat-based failure detection.
- **Heartbeat:** Workers send periodic heartbeats to coordinator. If coordinator misses 3 consecutive heartbeats (e.g., 5s timeout), mark worker as dead.
- **Task retry:** If a worker dies mid-stage, coordinator:
  1. Marks all tasks assigned to that worker as failed
  2. Reassigns those tasks to surviving workers
  3. Re-executes from the beginning of that stage (stages are deterministic)
- **Idempotency:** Tasks must be idempotent — re-running produces the same output.
- **Speculative execution (stretch):** If a task is slow, launch a duplicate on another worker. Take whichever finishes first.
- Deliverable: Kill a worker mid-query (via signal), observe coordinator reschedule and query complete successfully. Benchmark: recovery time < 2 seconds.

### Key Concepts
- MPP (Massively Parallel Processing) architecture
- Shuffle: the most expensive operation in distributed queries
- Data locality: schedule computation where the data lives
- Pipelining vs. blocking operators (hash join build is blocking, probe is pipelining)
- Two-phase aggregation: partial aggregate locally, then final aggregate after shuffle
- Fault tolerance: at-least-once execution with idempotent operators

### What to Read
- [Spark SQL: Relational Data Processing in Spark](https://people.csail.mit.edu/matei/papers/2015/sigmod_spark_sql.pdf)
- [Presto: SQL on Everything](https://trino.io/Presto_SQL_on_Everything.pdf)
- [Google Dremel: Interactive Analysis of Web-Scale Datasets](https://research.google/pubs/pub36632/)
- [F1: A Distributed SQL Database That Scales](https://research.google/pubs/pub41344/)
- [ClickHouse documentation on distributed query execution](https://clickhouse.com/docs/en/development/architecture)

### Interview Talking Point
> "I built distributed query execution with hash-based shuffles over gRPC, running TPC-H across 4 nodes. When a worker dies mid-query, the coordinator detects via heartbeat timeout and reschedules the stage within 2 seconds. The shuffle layer uses streaming RPCs with back-pressure to avoid OOM on receivers."

---

## Phase 4: AI/ML Extensions (Weeks 15–18)

**Goal:** Extend the engine to support AI workloads — vector search, feature serving.

### Milestones

**Week 15:** Vector column type. Store `float[]` embeddings as a first-class column type. Implement brute-force KNN as a baseline.
- Add `VECTOR(dim)` type to the type system. E.g., `VECTOR(768)` for sentence embeddings.
- Storage: Fixed-width column of `dim * 4` bytes per value. Aligned for SIMD.
- Brute-force KNN: Compute L2 distance / cosine similarity to query vector for all rows. Return top-K.
- Vectorized distance computation: tight loop over float arrays, JIT will auto-vectorize.
- Deliverable: Load 100K vectors, run brute-force KNN. Measure throughput (queries/sec) and recall.

**Week 16:** HNSW index. Build an approximate nearest neighbor index.
- Implement HNSW (Hierarchical Navigable Small World) graph:
  - Multi-layer graph. Top layers are sparse (long-range links), bottom layer is dense.
  - Insertion: find approximate nearest neighbors via greedy search, add edges.
  - Search: start from entry point, greedily descend layers, search bottom layer.
- Parameters: `M` (max connections per node), `efConstruction` (build-time beam width), `efSearch` (query-time beam width).
- SQL syntax: `SELECT * FROM docs ORDER BY embedding <-> ? LIMIT 10`
  - `<->` operator means "distance to". Optimizer recognizes this pattern and uses HNSW index.
- Deliverable: 1M vectors, 128 dimensions. Achieve >95% recall@10 with sub-millisecond query latency.

**Week 17:** Feature serving. Low-latency point lookups by key (like an online feature store).
- Use case: ML model needs features for a user/item at serving time.
- Point lookup: Given a primary key, return the feature row. Target: < 1ms p99.
- Architecture:
  - Hot features: LRU cache in memory (bounded by size).
  - Cold features: Read from columnar storage (use row group index on primary key).
  - Batch preloading: Before a prediction batch, prefetch all needed keys.
- SQL interface: `SELECT features FROM user_features WHERE user_id = ?` (optimized path).
- Deliverable: Feature lookup benchmark: X thousand lookups/sec with p99 < 1ms for cached features.

**Week 18:** Batch inference pipeline. Scan table → apply model (ONNX Runtime) → write results.
- Pipeline: `SELECT onnx_predict(model_name, col1, col2, ...) FROM input_table`
- Integration with ONNX Runtime Java binding:
  - Load model once per worker.
  - For each batch: marshal column vectors → ONNX tensor → run inference → unmarshal output.
- End-to-end demo:
  1. Embed text documents (sentence-transformers model via ONNX)
  2. Store embeddings in vector column
  3. Query: `SELECT title FROM docs ORDER BY embedding <-> embed('search query') LIMIT 10`
- Deliverable: End-to-end RAG pipeline running in your query engine. Benchmark: throughput of batch embedding.

### Key Concepts
- Vector indexes: HNSW, IVF, PQ (product quantization)
- Approximate vs. exact nearest neighbor tradeoffs (recall vs. latency)
- Feature stores: online vs. offline, point lookups vs. batch
- Hybrid queries: combine traditional filters with vector search
- Model serving: batch inference vs. real-time inference

### What to Read
- [HNSW paper: Efficient and robust approximate nearest neighbor using hierarchical navigable small world graphs](https://arxiv.org/abs/1603.09320)
- [Pinecone: What is a Vector Database?](https://www.pinecone.io/learn/vector-database/)
- [Weaviate architecture blog](https://weaviate.io/blog)
- [Feast documentation](https://docs.feast.dev/) — feature store concepts
- [ONNX Runtime Java API](https://onnxruntime.ai/docs/get-started/with-java.html)

### Interview Talking Point
> "I extended my query engine with HNSW-based vector search as a first-class SQL operator, supporting sub-millisecond approximate nearest neighbor queries over 1M 128-dimensional vectors with >95% recall@10. The engine also supports batch inference pipelines via ONNX Runtime integration, enabling end-to-end RAG workflows inside the query engine."

---

## Non-functional Requirements

### Benchmarked
Every phase must have benchmarks. No exceptions.
- **Storage:** Read/write throughput (GB/s), compression ratios by encoding, row-group skip rate
- **Execution:** Rows/sec for each TPC-H query, latency percentiles (p50, p95, p99)
- **Distributed:** Query latency vs. number of nodes, shuffle throughput, recovery time
- **Vector:** Queries/sec, recall@K, index build time, memory usage

Use [JMH](https://openjdk.org/projects/code-tools/jmh/) for micro-benchmarks. Write a benchmark harness for end-to-end query benchmarks.

### Tested
- **Unit tests:** Every operator, every encoding, every data type boundary condition.
- **Integration tests:** End-to-end queries against known datasets with expected results.
- **Property-based tests:** Encode → decode roundtrip for every encoding. Shuffle → verify no data loss.
- **Fuzz tests (stretch):** Feed random SQL to the parser, random data to the storage layer.

### Documented
- **Architecture Decision Records (ADRs):** For every major design choice:
  - Why columnar over row-oriented?
  - Why Volcano first, then vectorized?
  - Why gRPC over raw sockets?
  - Why hash join over sort-merge as the default?
  - Why HNSW over IVF for vector search?
- **Design docs:** Before implementing each phase, write a 1-2 page design doc.
- **Code comments:** Explain *why*, not *what*. Link to papers where applicable.

### Profiled
- Use [async-profiler](https://github.com/async-profiler/async-profiler) for CPU and allocation profiling.
- Use [Java Flight Recorder (JFR)](https://docs.oracle.com/en/java/javase/21/jfr/) for production-like profiling.
- For every benchmark, produce a flame graph. Know where time is spent.
- Target: zero unnecessary allocations in the hot path (execution loop).

---

## Repository Structure

```
query-engine/
├── build.gradle.kts           # Root build file
├── settings.gradle.kts        # Multi-module configuration
├── storage/                   # Columnar file format, reader, writer
│   ├── src/main/java/
│   │   ├── format/            # File layout, page format, footer
│   │   ├── encoding/          # Dictionary, RLE, delta, plain
│   │   ├── compression/       # Snappy, Zstd wrappers
│   │   ├── writer/            # ColumnWriter, FileWriter
│   │   └── reader/            # ColumnReader, FileReader, predicate pushdown
│   └── src/test/java/
├── catalog/                   # Table metadata, schema management
│   ├── src/main/java/
│   │   ├── schema/            # Column, DataType, Schema
│   │   ├── table/             # TableMetadata, PartitionInfo
│   │   └── stats/             # Column statistics, histograms
│   └── src/test/java/
├── parser/                    # SQL parsing, AST
│   ├── src/main/java/
│   │   ├── ast/               # SQL AST nodes
│   │   ├── analyzer/          # Semantic analysis, type resolution
│   │   └── converter/         # AST → Logical plan
│   └── src/test/java/
├── planner/                   # Logical plan, optimizer, physical plan
│   ├── src/main/java/
│   │   ├── logical/           # RelNode hierarchy
│   │   ├── optimizer/         # Rule interface, rules, fixed-point engine
│   │   ├── physical/          # Physical operator planning
│   │   └── distributed/       # Stage splitting, Exchange insertion
│   └── src/test/java/
├── execution/                 # Operators, vectorized engine
│   ├── src/main/java/
│   │   ├── vector/            # ColumnVector, RecordBatch, SelectionVector
│   │   ├── operator/          # Scan, Filter, Project, Join, Aggregate, Sort
│   │   ├── expression/        # Expression evaluation (vectorized)
│   │   └── memory/            # Buffer pool, memory accounting
│   └── src/test/java/
├── network/                   # gRPC services, shuffle
│   ├── src/main/java/
│   │   ├── proto/             # Protobuf definitions
│   │   ├── shuffle/           # ShuffleWriter, ShuffleReader
│   │   └── service/           # gRPC service implementations
│   └── src/test/java/
├── coordinator/               # Distributed planning, scheduling
│   ├── src/main/java/
│   │   ├── planner/           # Distributed query planner
│   │   ├── scheduler/         # Task scheduler, worker management
│   │   ├── heartbeat/         # Failure detection
│   │   └── server/            # Coordinator gRPC server
│   └── src/test/java/
├── worker/                    # Task execution, local engine
│   ├── src/main/java/
│   │   ├── executor/          # Stage executor
│   │   ├── task/              # Task lifecycle
│   │   └── server/            # Worker gRPC server
│   └── src/test/java/
├── vector-index/              # HNSW index, vector operations
│   ├── src/main/java/
│   │   ├── hnsw/              # HNSW graph, search, construction
│   │   ├── distance/          # L2, cosine, dot product
│   │   └── serving/           # Feature store, point lookups
│   └── src/test/java/
├── bench/                     # Benchmarks (JMH)
│   ├── src/main/java/
│   │   ├── storage/           # Read/write benchmarks
│   │   ├── execution/         # Operator benchmarks
│   │   └── tpch/              # TPC-H query benchmarks
│   └── build.gradle.kts
├── tests/                     # Integration tests
│   ├── src/test/java/
│   │   ├── e2e/               # End-to-end SQL tests
│   │   ├── distributed/       # Multi-node tests
│   │   └── correctness/       # Result verification
│   └── build.gradle.kts
└── docs/                      # Design documents
    ├── adr/                   # Architecture Decision Records
    ├── design/                # Phase design docs
    └── benchmarks/            # Benchmark results and analysis
```

---

## Tech Stack

| Component       | Choice                          | Rationale                                                    |
|-----------------|---------------------------------|--------------------------------------------------------------|
| Language        | Java 21+                        | Virtual threads, modern APIs, target company ecosystem       |
| Build           | Gradle (Kotlin DSL)             | Faster incremental builds than Maven, better multi-module    |
| SQL Parser      | ANTLR4 with custom SQL grammar  | More control than JSqlParser, learn parser generators         |
| Networking      | gRPC-java                       | Streaming RPCs, protobuf, industry standard for services     |
| Compression     | snappy-java, zstd-jni           | Snappy for speed, Zstd for ratio. Both industry standard     |
| Serialization   | Protobuf (network), custom binary (storage) | Protobuf for inter-node; custom binary for perf-critical storage |
| Testing         | JUnit 5                         | Standard. Use `@ParameterizedTest` heavily                   |
| Benchmarks      | JMH                             | The only correct way to micro-benchmark on JVM               |
| Profiling       | async-profiler, JFR             | CPU flame graphs, allocation tracking, lock profiling        |
| Memory          | `sun.misc.Unsafe` / `MemorySegment` (Panama) | Off-heap buffers for large data, avoid GC pressure  |
| Logging         | SLF4J + Logback                 | Standard, structured logging                                 |

### Dependency Versions (pin these)

```kotlin
// build.gradle.kts
val grpcVersion = "1.62.2"
val protobufVersion = "3.25.3"
val antlrVersion = "4.13.1"
val snappyVersion = "1.1.10.5"
val zstdVersion = "1.5.5-11"
val junitVersion = "5.10.2"
val jmhVersion = "1.37"
val onnxruntimeVersion = "1.17.0"
val slf4jVersion = "2.0.12"
```

---

## How to Use This Document

1. **Work one phase at a time.** Don't skip ahead. Each phase builds on the previous one. Resist the urge to start distributed execution before your single-node engine is solid.

2. **At the end of each phase, benchmark and write an ADR.** The benchmark proves it works and gives you concrete numbers for interviews. The ADR forces you to articulate *why* you made each decision.

3. **Use AI as a reviewer.** Before implementing a component:
   - Show the design (interfaces, data flow, edge cases).
   - Get feedback.
   - Then implement.
   - Then show the code for review.

4. **Don't optimize prematurely.** Get it correct first (Volcano model), then make it fast (vectorized). The performance comparison between the two is itself an interview talking point.

5. **If stuck for more than 2 hours on a concept, ask.** If stuck on syntax/API/library usage, read the docs yourself — that's the skill you're building.

6. **Keep a learning journal.** After each week, write 3-5 sentences: what you built, what was hard, what you'd do differently. This is gold for behavioral interviews ("tell me about a time you faced a technical challenge").

7. **Test against known results.** Use TPC-H as your correctness oracle. The expected results for TPC-H queries at scale factor 1 are published. If your results don't match, you have a bug.

---

## Timeline Summary

| Phase | Weeks | Deliverable | Interview Signal |
|-------|-------|-------------|-----------------|
| 1. Columnar Storage | 1–4 | Custom columnar format with encoding + compression | Storage engineering depth |
| 2. Query Execution | 5–9 | Vectorized SQL engine running TPC-H | Query engine internals |
| 3. Distributed | 10–14 | Multi-node execution with fault tolerance | Distributed systems |
| 4. AI/ML Extensions | 15–18 | Vector search + feature serving + inference | AI infrastructure |

**Total: ~18 weeks (4.5 months) of focused weekend/evening work.**

---

## Why This Project Lands Interviews

| Company | What They Care About | What You Demonstrate |
|---------|---------------------|---------------------|
| Databricks | Spark internals, query optimization | Distributed planning, shuffle, vectorized execution |
| Snowflake | Columnar storage, query processing | Custom file format, encoding schemes, zone maps |
| ClickHouse | Columnar engine, compression, performance | Storage engine, vectorized execution, benchmarks |
| Google (BigQuery/Spanner) | Distributed query processing | Coordinator/worker, fault tolerance, Dremel-style execution |
| Uber (Presto/Pinot) | Real-time analytics infrastructure | End-to-end query engine, feature serving |
| LinkedIn (Pinot) | Columnar storage, real-time serving | Storage + low-latency point lookups |
| Databricks (AI) | Vector search, ML infrastructure | HNSW, feature store, batch inference |

---

## Getting Started (First Day)

```bash
# Create the project
mkdir query-engine && cd query-engine
gradle init --type java-library --dsl kotlin

# Set up multi-module structure
# (Configure settings.gradle.kts with subprojects)

# First file to write:
# storage/src/main/java/com/vksql/storage/format/DataType.java
public enum DataType {
    INT32(4),
    INT64(8),
    FLOAT64(8),
    STRING(-1);  // variable length

    private final int byteWidth;
    // ...
}
```

Start with the simplest possible thing: write 1 million integers to a file in columnar format, read them back, verify correctness. Then iterate from there.

**Good luck. Build something you're proud to talk about for 45 minutes in a system design interview.**
