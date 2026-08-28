# Architecture

Detailed technical architecture of vkSQL — a distributed analytical query engine.

## System Overview

```mermaid
graph TD
    subgraph vkSQL Engine
        SQL[SQL Input] --> Parser[Parser<br/>ANTLR4]
        Parser --> LP[Logical Plan]
        LP --> Opt[Optimizer]
        Opt --> PP[Physical Plan]
        PP --> Exec[Execution Engine<br/>Vectorized Operators<br/>Scan · Filter · Join · Agg · Project · Sort]
        Exec --> Storage[Storage Engine<br/>Columnar File .vkql<br/>mmap Reader / Writer]
    end

    subgraph Distributed Layer
        Coord[Coordinator] -->|gRPC| W1[Worker 1<br/>Executor + Storage]
        Coord -->|gRPC| W2[Worker 2<br/>Executor + Storage]
        Coord -->|gRPC| WN[Worker N<br/>Executor + Storage]
        W1 <-->|shuffle| W2
        W2 <-->|shuffle| WN
    end
```

## Data Flow

### Query Processing Pipeline

```mermaid
flowchart TD
    Q["SQL: SELECT sum(amount) FROM orders WHERE region = 'US'"] --> Lexer

    Lexer["1. LEXER (ANTLR4)<br/>SQL string → token stream<br/>[SELECT][sum][amount][FROM]..."]
    Lexer --> ParserStep

    ParserStep["2. PARSER (ANTLR4)<br/>token stream → parse tree (CST)<br/>SelectStmt → SelectList → ..."]
    ParserStep --> AST

    AST["3. AST BUILDER (Visitor)<br/>parse tree → abstract syntax tree<br/>Query { projection, filter, ... }"]
    AST --> LogicalPlan

    LogicalPlan["4. LOGICAL PLAN<br/>AST → relational algebra tree<br/>Aggregate(Filter(Scan(orders)))"]
    LogicalPlan --> Optimizer

    Optimizer["5. OPTIMIZER<br/>Predicate pushdown<br/>Projection pruning<br/>Join reordering"]
    Optimizer --> PhysicalPlan

    PhysicalPlan["6. PHYSICAL PLAN<br/>Choose operator implementations<br/>HashAggregate(Scan(orders, filter=region='US'))"]
    PhysicalPlan --> Execution

    Execution["7. EXECUTION<br/>Vectorized batch processing<br/>RecordBatch (1024 rows) at a time<br/>→ Result: sum = 4,521,300"]
```

## Storage Format

### File Layout

vkSQL uses a custom columnar file format (`.vkql`) optimized for analytical workloads:

```mermaid
flowchart TD
    subgraph File[".vkql File Layout"]
        direction TB
        H["Magic: VKQL (4 bytes)"]
        subgraph RG0["Row Group 0 (1M rows)"]
            C0["Column 0: Page 0 | Page 1 | ..."]
            C1["Column 1: Page 0 | Page 1 | ..."]
            C2["Column 2: Page 0 | Page 1 | ..."]
        end
        subgraph RG1["Row Group 1 (1M rows)"]
            C3["Column 0 | Column 1 | Column 2"]
        end
        subgraph Footer["Footer (metadata)"]
            F1["Schema: column names + types"]
            F2["Row Group metadata: offset, size, row count"]
            F3["Per-column stats: min, max, null count"]
        end
        FL["Footer Length (4 bytes)"]
        M["Magic: VKQL (4 bytes)"]
    end
    H --> RG0 --> RG1 --> Footer --> FL --> M
```

### Page Structure

Each page contains a fixed number of values for a single column:

```mermaid
flowchart TD
    subgraph Page["Page Structure (~64KB)"]
        direction TB
        PH["Bitmap Size (4 bytes)"]
        NB["Null Bitmap: 1 bit per value"]
        ED["Encoded Data: Dictionary / RLE / Delta / Plain"]
    end
    PH --> NB --> ED
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

**Operator tree (pull-based pipeline):**

```mermaid
flowchart BT
    Scan["TableScan [orders]<br/>mmap + zone map pruning"] -->|"next()"| Filter
    Filter["Filter [amount > 100]"] -->|"next()"| Agg
    Agg["HashAggregate<br/>[sum(amount), group by region]"]
```

> Each operator pulls batches from its child by calling `next()`. Data flows upward from scan to the root operator.

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

```mermaid
flowchart TD
    subgraph Coordinator
        QP[Query Planning + Fragment Distribution]
        PA[Partition Assignment + Result Aggregation]
    end

    Coordinator -->|gRPC| W1
    Coordinator -->|gRPC| W2
    Coordinator -->|gRPC| WN

    subgraph W1[Worker 1]
        E1[Executor<br/>local]
        S1[Storage<br/>local]
    end

    subgraph W2[Worker 2]
        E2[Executor<br/>local]
        S2[Storage<br/>local]
    end

    subgraph WN[Worker N]
        EN[Executor<br/>local]
        SN[Storage<br/>local]
    end

    W1 <-->|shuffle| W2
    W2 <-->|shuffle| WN
    W1 <-->|shuffle| WN
```

### Query Execution Flow (Distributed)

```mermaid
sequenceDiagram
    participant Client
    participant Coordinator
    participant Worker1 as Worker 1
    participant Worker2 as Worker 2
    participant WorkerN as Worker N

    Client->>Coordinator: SQL Query
    Coordinator->>Coordinator: Parse & Plan (physical plan)
    Coordinator->>Coordinator: Fragment plan by partitions

    par Assign & Execute
        Coordinator->>Worker1: Fragment A (partitions 0-3)
        Coordinator->>Worker2: Fragment B (partitions 4-7)
        Coordinator->>WorkerN: Fragment C (partitions 8-11)
    end

    par Local Execution
        Worker1->>Worker1: Execute on local data
        Worker2->>Worker2: Execute on local data
        WorkerN->>WorkerN: Execute on local data
    end

    par Shuffle (hash-partitioned)
        Worker1->>Worker2: Shuffle intermediate results
        Worker2->>WorkerN: Shuffle intermediate results
        WorkerN->>Worker1: Shuffle intermediate results
    end

    Worker1->>Coordinator: Partial result
    Worker2->>Coordinator: Partial result
    WorkerN->>Coordinator: Partial result

    Coordinator->>Coordinator: Aggregate final result
    Coordinator->>Client: Final result
```

### Distributed Execution Stages

```mermaid
flowchart LR
    subgraph Stage1[Stage 1: Scan + Filter]
        S1[Scan Partition 0-3]
        S2[Scan Partition 4-7]
        S3[Scan Partition 8-11]
    end

    subgraph Exchange[Exchange Operator<br/>Hash Repartition on Join Key]
        EX[gRPC Shuffle]
    end

    subgraph Stage2[Stage 2: Join + Aggregate]
        J1[HashJoin + Agg<br/>Key Group 0]
        J2[HashJoin + Agg<br/>Key Group 1]
        J3[HashJoin + Agg<br/>Key Group 2]
    end

    subgraph Final[Final Stage]
        Merge[Merge Results]
    end

    S1 --> EX
    S2 --> EX
    S3 --> EX
    EX --> J1
    EX --> J2
    EX --> J3
    J1 --> Merge
    J2 --> Merge
    J3 --> Merge
```

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

## HNSW Index Structure (Planned)

For the upcoming AI/ML extensions (Phase 6), vkSQL will support HNSW (Hierarchical Navigable Small World) indexing for approximate nearest neighbor vector search:

```mermaid
flowchart TD
    subgraph Layer2["Layer 2 (top — fewest nodes, long-range links)"]
        L2A((A)) <--> L2D((D))
        L2D <--> L2G((G))
        L2A <--> L2G
    end

    subgraph Layer1["Layer 1 (middle — more nodes, medium links)"]
        L1A((A)) <--> L1B((B))
        L1B <--> L1D((D))
        L1D <--> L1E((E))
        L1E <--> L1G((G))
        L1A <--> L1D
        L1G <--> L1A
    end

    subgraph Layer0["Layer 0 (bottom — all nodes, short-range links)"]
        L0A((A)) <--> L0B((B))
        L0B <--> L0C((C))
        L0C <--> L0D((D))
        L0D <--> L0E((E))
        L0E <--> L0F((F))
        L0F <--> L0G((G))
        L0A <--> L0C
        L0B <--> L0D
        L0C <--> L0E
        L0D <--> L0F
        L0E <--> L0G
    end

    L2A -.->|"enter"| L1A
    L2D -.-> L1D
    L2G -.-> L1G
    L1A -.-> L0A
    L1B -.-> L0B
    L1D -.-> L0D
    L1E -.-> L0E
    L1G -.-> L0G
```

> Search begins at the top layer with long-range jumps, then descends to lower layers for finer-grained navigation. Each layer is a navigable small-world graph with increasing density.

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
