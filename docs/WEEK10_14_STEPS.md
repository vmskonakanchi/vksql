# Weeks 10–14: Distributed Execution

## What You're Building

A distributed query engine that partitions data across multiple nodes, plans queries as stage DAGs, shuffles intermediate results over gRPC, and recovers from failures. By the end you'll execute TPC-H queries across N worker nodes coordinated by a single coordinator process.

---

## Why Distributed?

A single node can hold ~64GB in RAM. TPC-H SF100 is 100GB raw CSV — doesn't fit. Even if it did, a single CPU can only parallelize across local cores. Distributing across N nodes gives you:
- **N× memory** — partition data so each node holds 1/N of a table
- **N× CPU** — each node computes partial aggregates in parallel
- **Fault isolation** — one bad node doesn't kill the whole query

The challenge: data that needs to be combined (e.g., GROUP BY on a key) might live on different nodes. You need to **shuffle** it to the right place.

---

---

# Week 10: Data Partitioning

## What You're Building

A system that splits table data across N nodes using hash partitioning, so every row has a deterministic home based on its partition key.

---

## Step 1: Understand Hash Partitioning

The core formula:

```
partition_id = hash(key) % N
```

Where:
- `key` — the column value you're partitioning on (e.g., `customer_id`)
- `N` — number of partitions (typically = number of nodes)
- `hash()` — a deterministic hash function (use MurmurHash3 — fast, good distribution)

**Why hash and not range?** Hash gives uniform distribution without needing to know data distribution upfront. Range partitioning (key 0–999 → node 0, 1000–1999 → node 1) requires knowing min/max and risks skew.

**Syntax hint — MurmurHash3 for ints (simplified):**
```java
public static int hash(int key) {
    key ^= key >>> 16;
    key *= 0x85ebca6b;
    key ^= key >>> 13;
    key *= 0xc2b2ae35;
    key ^= key >>> 16;
    return key;
}

// For partition assignment:
int partitionId = Math.floorMod(hash(key), numPartitions);
```

**Important:** Use `Math.floorMod` not `%` — the modulo operator in Java can return negative values for negative inputs.

---

## Step 2: Define the Partition Scheme

**File:** `network/src/main/java/com/vksql/network/partition/PartitionScheme.java`

```java
public record PartitionScheme(
    String tableName,
    String partitionColumn,
    int numPartitions,
    PartitionType type  // HASH, BROADCAST, SINGLE
) {}
```

Three partition types:
- **HASH** — row goes to `hash(key) % N` (most tables)
- **BROADCAST** — small tables replicated to all nodes (dimension tables in star schemas)
- **SINGLE** — all data on one node (result collection)

---

## Step 3: Build PartitionManager

**File:** `network/src/main/java/com/vksql/network/partition/PartitionManager.java`

Maps tables to their partition schemes and tracks which files live on which nodes.

```java
public class PartitionManager {
    // table_name → partition scheme
    private final Map<String, PartitionScheme> schemes;
    // table_name → (partition_id → node_id)
    private final Map<String, Map<Integer, String>> assignments;
    // node_id → address (host:port)
    private final Map<String, NodeAddress> nodeRegistry;

    public PartitionScheme getScheme(String table) { ... }
    public String getNodeForPartition(String table, int partitionId) { ... }
    public List<String> getNodesForTable(String table) { ... }
    public void registerNode(String nodeId, NodeAddress addr) { ... }
}
```

Think of this as the metadata catalog for a distributed system — it answers "where is the data?"

---

## Step 4: Build DataLoader — Split CSV Across Partitions

**File:** `network/src/main/java/com/vksql/network/partition/DataLoader.java`

This tool reads a CSV, hashes each row, and writes it to the appropriate partition directory.

```java
public class DataLoader {
    private final PartitionScheme scheme;
    private final Schema schema;

    /**
     * Splits a CSV file into N partition directories.
     * Output: baseDir/partition_0/table.vksql, baseDir/partition_1/table.vksql, ...
     */
    public void load(Path csvFile, Path baseDir) throws IOException {
        // 1. Open N VksqlFileWriters (one per partition)
        // 2. Read CSV line by line
        // 3. Parse the partition column value
        // 4. Compute partition_id = floorMod(hash(value), N)
        // 5. Write row to the appropriate writer
        // 6. Close all writers
    }
}
```

**Directory layout after loading `lineitem.csv` with N=4:**
```
data/
  partition_0/
    lineitem.vksql
  partition_1/
    lineitem.vksql
  partition_2/
    lineitem.vksql
  partition_3/
    lineitem.vksql
```

**Syntax hint — reading CSV:**
```java
try (var reader = Files.newBufferedReader(csvFile)) {
    String header = reader.readLine();  // skip or parse column names
    String line;
    while ((line = reader.readLine()) != null) {
        String[] fields = line.split(",");
        int key = Integer.parseInt(fields[partitionColIndex]);
        int pid = Math.floorMod(hash(key), numPartitions);
        writers[pid].writeRow(parseRow(fields, schema));
    }
}
```

---

## Step 5: Write Tests

```java
@Test
void hashPartitioningIsStable() {
    // Same key always maps to same partition
    int p1 = Math.floorMod(hash(42), 4);
    int p2 = Math.floorMod(hash(42), 4);
    assertEquals(p1, p2);
}

@Test
void dataLoaderSplitsEvenly() {
    // Load 10000 rows → 4 partitions
    // Each partition should have ~2500 rows (within 20% tolerance)
}

@Test
void coPartitionedTablesAlignOnJoinKey() {
    // orders and lineitem both partitioned on order_key
    // For any order_key, both rows land in the same partition
}
```

---

## Common Mistakes

1. **Using `%` instead of `Math.floorMod`** — `-7 % 4` returns `-3` in Java, not `1`. Always use `Math.floorMod`.
2. **Hashing strings incorrectly** — if the partition key is a string, hash its bytes, not `hashCode()` (which isn't stable across JVM versions).
3. **Forgetting to close all writers** — if you open N writers, you must close all N even if one fails. Use try-with-resources or a finally block.
4. **Uneven partitions** — if your hash function is poor or the data is skewed, one partition gets most of the data. MurmurHash3 handles this well for random keys.

---

---

# Week 11: Distributed Query Planning

## What You're Building

A planner that takes a single-node logical plan and converts it into a **stage DAG** — a directed acyclic graph of stages connected by shuffle (exchange) boundaries.

---

## Step 1: Understand the Exchange Operator

An **Exchange** is a logical operator that marks where data must move between nodes. It doesn't compute anything — it's a boundary marker.

```
Single-node plan:              Distributed plan:
                               
  Aggregate(SUM)                 Stage 2: FinalAggregate(SUM)
       |                              |
    Scan(lineitem)                  Exchange(HASH on key)
                                      |
                               Stage 1: PartialAggregate(SUM)
                                      |
                                   Scan(lineitem)
```

**Exchange types:**
- `HASH` — repartition by hash of key (for GROUP BY, JOIN)
- `BROADCAST` — send all data to all nodes (small table in broadcast join)
- `GATHER` — send all data to coordinator (final result collection)

---

## Step 2: Define Exchange Operator

**File:** `planner/src/main/java/com/vksql/planner/physical/ExchangeOperator.java`

```java
public record ExchangeOperator(
    PhysicalOperator child,
    ExchangeType type,          // HASH, BROADCAST, GATHER
    List<String> partitionKeys, // columns to hash on (empty for GATHER/BROADCAST)
    int targetPartitions        // N
) implements PhysicalOperator {}
```

---

## Step 3: Understand Stage DAG

A query becomes multiple **stages**. Each stage runs independently on one or more nodes. Stages are connected by exchanges (shuffle boundaries).

Example: `SELECT l_partkey, SUM(l_quantity) FROM lineitem GROUP BY l_partkey`

```
Stage 0 (runs on ALL nodes, in parallel):
  PartialAggregate(key=l_partkey, func=SUM(l_quantity))
    → Scan(lineitem, local partition)

  Output: partial sums per key (still partitioned by original hash)

--- Exchange: HASH repartition on l_partkey ---

Stage 1 (runs on ALL nodes, in parallel):
  FinalAggregate(key=l_partkey, func=MERGE_SUM)
    → Read shuffled data

  Output: final sum per key (each node has a disjoint subset of keys)

--- Exchange: GATHER to coordinator ---

Stage 2 (runs on COORDINATOR only):
  Collect all results
  Return to client
```

---

## Step 4: Define Stage

**File:** `planner/src/main/java/com/vksql/planner/distributed/Stage.java`

```java
public record Stage(
    int stageId,
    PhysicalOperator rootOperator,   // the operator tree for this stage
    ExchangeType outputType,         // how this stage sends data out
    List<String> outputPartitionKeys,
    List<Integer> inputStageIds      // stages that feed into this one
) {}
```

**File:** `planner/src/main/java/com/vksql/planner/distributed/StageDAG.java`

```java
public class StageDAG {
    private final List<Stage> stages;  // topologically sorted

    public Stage getStage(int id) { ... }
    public List<Stage> getRootStages() { ... }     // no dependencies (leaf scans)
    public List<Stage> getReadyStages(Set<Integer> completed) { ... }
}
```

---

## Step 5: Build DistributedPlanner

**File:** `planner/src/main/java/com/vksql/planner/distributed/DistributedPlanner.java`

This is the main class. It walks the physical plan tree and:
1. Identifies where exchanges are needed (before aggregates with GROUP BY, before joins on non-co-partitioned keys)
2. Inserts `ExchangeOperator` nodes
3. Cuts the plan at exchange boundaries into stages
4. Builds the `StageDAG`

```java
public class DistributedPlanner {
    private final PartitionManager partitionManager;
    private int nextStageId = 0;

    public StageDAG plan(PhysicalOperator root) {
        List<Stage> stages = new ArrayList<>();
        // Walk bottom-up:
        // 1. Each scan becomes the start of a new stage
        // 2. When you hit an aggregate, check if data needs repartitioning
        // 3. If yes, cut here: current ops become one stage, ops above become next stage
        // 4. Add GATHER exchange at the top
        buildStages(root, stages);
        return new StageDAG(stages);
    }
}
```

**Key rule:** A stage boundary is needed when the **required partitioning** of an operator doesn't match the **current partitioning** of its input.
- `Aggregate(GROUP BY col)` requires data partitioned on `col`
- `HashJoin(left.key = right.key)` requires both inputs partitioned on the join key
- If the table is already partitioned on that key → no exchange needed!

---

## Step 6: Handle Two-Phase Aggregation

Single-node aggregation doesn't work distributed. You must split into:
1. **Partial aggregate** — each node computes local aggregates (SUM becomes local SUM, COUNT becomes local COUNT)
2. **Final aggregate** — after shuffle, merge partial results (SUM of SUMs, SUM of COUNTs)

For AVG: split into SUM + COUNT locally, then SUM(sums)/SUM(counts) in final.

Your planner should detect `AggregateOperator` and rewrite it into `PartialAggregateOperator` + `Exchange` + `FinalAggregateOperator`.

---

## Step 7: Write Tests

```java
@Test
void simpleAggregateProduces3Stages() {
    // SELECT SUM(qty) FROM orders GROUP BY customer_id
    // Stage 0: Scan + PartialAgg
    // Stage 1: FinalAgg (after HASH exchange)
    // Stage 2: Gather
    var dag = planner.plan(aggPlan);
    assertEquals(3, dag.getStages().size());
}

@Test
void coPartitionedJoinSkipsExchange() {
    // orders and lineitem both partitioned on order_key
    // Join on order_key → no exchange needed between them
}

@Test
void nonCoPartitionedJoinAddsExchange() {
    // Join on a key that doesn't match the partition key
    // → exchange needed on one or both sides
}
```

---

## Common Mistakes

1. **Forgetting the GATHER stage** — every distributed query needs a final gather to collect results at the coordinator. Without it, results stay scattered.
2. **Not splitting aggregation into two phases** — a single-node SUM gives wrong results if data is shuffled mid-computation.
3. **Inserting unnecessary exchanges** — if data is already partitioned on the GROUP BY key, you don't need to reshuffle. Check `partitionManager.getScheme()` first.
4. **Wrong stage ordering** — stages must be topologically sorted. A stage can't run until all its input stages are complete.

---

---

# Week 12: Shuffle (gRPC)

## What You're Building

A network layer using gRPC that moves `RecordBatch` data between nodes during a shuffle. One node produces batches, hashes each row, and streams it to the appropriate destination node.

---

## Step 1: Set Up gRPC + Protobuf in Gradle

**File:** `network/build.gradle.kts`

```kotlin
plugins {
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.62.2")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("com.google.protobuf:protobuf-java:3.25.3")

    // For javax.annotation (gRPC generated code needs it)
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
```

**Proto files go in:** `network/src/main/proto/`

Run `./gradlew :network:generateProto` to generate Java stubs.

---

## Step 2: Define Protobuf Messages for RecordBatch

**File:** `network/src/main/proto/shuffle.proto`

```protobuf
syntax = "proto3";

package vksql.shuffle;

option java_package = "com.vksql.network.proto";
option java_multiple_files = true;

// Schema description
message ColumnSchema {
    string name = 1;
    DataType type = 2;
}

enum DataType {
    INT32 = 0;
    INT64 = 1;
    FLOAT64 = 2;
    STRING = 3;
}

// A batch of rows in columnar format
message RecordBatch {
    int32 num_rows = 1;
    repeated ColumnData columns = 2;
}

message ColumnData {
    string name = 1;
    DataType type = 2;
    bytes null_bitmap = 3;    // 1 bit per row, same as storage format
    bytes values = 4;         // raw encoded values (same layout as your pages)
    // For strings: offsets are prepended in values (same as your offset encoding)
}

// Shuffle metadata
message ShuffleHeader {
    int32 query_id = 1;
    int32 stage_id = 2;
    int32 partition_id = 3;   // which target partition this batch is for
    repeated ColumnSchema schema = 4;
}

message ShuffleData {
    oneof payload {
        ShuffleHeader header = 1;
        RecordBatch batch = 2;
    }
}

message ShuffleAck {
    bool success = 1;
    string error_message = 2;
    int64 bytes_received = 3;
}

// Service definition
service ShuffleService {
    // Client streams batches to a specific partition on this node
    rpc SendShuffle(stream ShuffleData) returns (ShuffleAck);

    // Server streams batches from a partition to the requester
    rpc ReceiveShuffle(ShuffleRequest) returns (stream RecordBatch);
}

message ShuffleRequest {
    int32 query_id = 1;
    int32 stage_id = 2;
    int32 partition_id = 3;
}
```

---

## Step 3: Implement ShuffleService (Server Side)

**File:** `network/src/main/java/com/vksql/network/shuffle/ShuffleServiceImpl.java`

```java
public class ShuffleServiceImpl extends ShuffleServiceGrpc.ShuffleServiceImplBase {

    // Buffers incoming shuffle data per (queryId, stageId, partitionId)
    private final ConcurrentHashMap<ShuffleKey, BlockingQueue<RecordBatch>> buffers
        = new ConcurrentHashMap<>();

    @Override
    public StreamObserver<ShuffleData> sendShuffle(StreamObserver<ShuffleAck> responseObserver) {
        return new StreamObserver<>() {
            private ShuffleKey key;
            private long bytesReceived = 0;

            @Override
            public void onNext(ShuffleData data) {
                if (data.hasHeader()) {
                    var h = data.getHeader();
                    key = new ShuffleKey(h.getQueryId(), h.getStageId(), h.getPartitionId());
                    buffers.putIfAbsent(key, new LinkedBlockingQueue<>());
                } else if (data.hasBatch()) {
                    buffers.get(key).offer(data.getBatch());
                    bytesReceived += data.getBatch().getSerializedSize();
                }
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(ShuffleAck.newBuilder()
                    .setSuccess(true)
                    .setBytesReceived(bytesReceived)
                    .build());
                responseObserver.onCompleted();
            }

            @Override
            public void onError(Throwable t) { /* log error, clean up */ }
        };
    }

    @Override
    public void receiveShuffle(ShuffleRequest req, StreamObserver<RecordBatch> responseObserver) {
        var key = new ShuffleKey(req.getQueryId(), req.getStageId(), req.getPartitionId());
        var queue = buffers.get(key);
        if (queue == null) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        // Drain all available batches
        RecordBatch batch;
        while ((batch = queue.poll()) != null) {
            responseObserver.onNext(batch);
        }
        responseObserver.onCompleted();
    }
}
```

---

## Step 4: Implement Shuffle Writer (Client Side)

**File:** `network/src/main/java/com/vksql/network/shuffle/ShuffleWriter.java`

This component takes a `RecordBatch` from the local executor, hashes each row, and streams it to the correct destination node.

```java
public class ShuffleWriter {
    private final int numPartitions;
    private final String[] partitionKeys;
    private final Map<Integer, StreamObserver<ShuffleData>> streams;  // partition → gRPC stream
    private final PartitionManager partitionManager;

    /**
     * Repartitions a batch and sends rows to the appropriate target nodes.
     */
    public void shuffleBatch(RecordBatch batch) {
        // 1. For each row in the batch:
        //    a. Extract the partition key column value
        //    b. Compute targetPartition = floorMod(hash(value), numPartitions)
        //    c. Append row to a per-partition buffer

        // 2. For each partition buffer that has enough rows (e.g., 1024):
        //    a. Build a RecordBatch proto message
        //    b. Send it on the appropriate gRPC stream

        // This is "hash-based repartitioning per batch"
    }
}
```

**Key optimization:** Don't send one row at a time. Buffer rows per destination and send in batches of ~1024 rows. This amortizes gRPC overhead.

---

## Step 5: Handle Back-Pressure with gRPC Flow Control

gRPC has built-in flow control. If the receiver is slow, the sender must pause.

**How it works:**
- gRPC uses HTTP/2 flow control windows
- `StreamObserver` has `isReady()` — returns false when the send buffer is full
- You register `onReadyHandler()` to get notified when you can send again

```java
// When creating the client stream:
CallStreamObserver<ShuffleData> requestObserver =
    (CallStreamObserver<ShuffleData>) stub.sendShuffle(responseObserver);

requestObserver.setOnReadyHandler(() -> {
    // Resume sending when buffer drains
    synchronized (lock) {
        lock.notifyAll();
    }
});

// Before sending:
while (!requestObserver.isReady()) {
    synchronized (lock) {
        lock.wait(100);  // Back off until ready
    }
}
requestObserver.onNext(shuffleData);
```

**Why this matters:** Without flow control, a fast producer overwhelms a slow consumer → OOM on the receiver side.

---

## Step 6: RecordBatch Serialization

Convert your in-memory `VectorizedBatch` (from the execution engine) to/from the protobuf `RecordBatch`.

**File:** `network/src/main/java/com/vksql/network/shuffle/BatchSerializer.java`

```java
public class BatchSerializer {

    public static RecordBatch toProto(VectorizedBatch batch) {
        var builder = RecordBatch.newBuilder().setNumRows(batch.getRowCount());
        for (int col = 0; col < batch.getColumnCount(); col++) {
            var colData = ColumnData.newBuilder()
                .setName(batch.getColumnName(col))
                .setType(mapType(batch.getColumnType(col)))
                .setNullBitmap(ByteString.copyFrom(batch.getNullBitmap(col)))
                .setValues(ByteString.copyFrom(batch.getRawValues(col)))
                .build();
            builder.addColumns(colData);
        }
        return builder.build();
    }

    public static VectorizedBatch fromProto(RecordBatch proto) {
        // Inverse: build VectorizedBatch from proto column data
    }
}
```

---

## Step 7: Start/Stop the gRPC Server

**File:** `network/src/main/java/com/vksql/network/shuffle/ShuffleServer.java`

```java
public class ShuffleServer {
    private final Server server;

    public ShuffleServer(int port) {
        this.server = ServerBuilder.forPort(port)
            .addService(new ShuffleServiceImpl())
            .maxInboundMessageSize(16 * 1024 * 1024)  // 16MB max message
            .build();
    }

    public void start() throws IOException {
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public void stop() {
        server.shutdown();
        try { server.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { server.shutdownNow(); }
    }
}
```

---

## Step 8: Write Tests

```java
@Test
void shuffleRoundTrip() throws Exception {
    // Start a ShuffleServer on localhost:9090
    // Create a ShuffleWriter that sends to localhost:9090
    // Send 10 batches of 1024 rows
    // Use ReceiveShuffle to read them back
    // Verify all rows arrived, in correct partitions
}

@Test
void backPressureDoesNotOOM() {
    // Set a tiny receive buffer
    // Send faster than receiver can consume
    // Verify no OOM, sender blocks appropriately
}
```

---

## Common Mistakes

1. **Serializing row-by-row over gRPC** — catastrophic overhead. Always send batches of 1000+ rows per message.
2. **Forgetting to send the header first** — the receiver doesn't know which (query, stage, partition) the data belongs to without the header.
3. **Not handling `onError` on the stream** — network failures happen. If you don't handle them, the sender hangs forever.
4. **Message size too large** — default gRPC max message size is 4MB. Set `maxInboundMessageSize` higher, or split large batches.
5. **Forgetting `server.awaitTermination()`** — without it, the JVM exits before in-flight RPCs complete.

---

---

# Week 13: Coordinator

## What You're Building

The **Coordinator** is the single entry point for queries. It receives SQL, plans the distributed execution, assigns stages to workers, and collects results.

---

## Step 1: Understand the Architecture

```
Client → Coordinator → Workers (N nodes)

1. Client sends SQL to Coordinator
2. Coordinator parses, plans, produces StageDAG
3. Coordinator sends Stage 0 (leaf scans) to workers
4. Workers execute, shuffle intermediate results
5. Coordinator sends Stage 1 (post-shuffle) to workers
6. ... repeat for all stages ...
7. Final stage gathers results to coordinator
8. Coordinator returns results to client
```

The coordinator does NOT execute data-intensive work. It only:
- Plans
- Schedules stages
- Tracks progress
- Collects final results

---

## Step 2: Define the Coordinator RPC Interface

**File:** `network/src/main/proto/coordinator.proto`

```protobuf
syntax = "proto3";

package vksql.coordinator;

option java_package = "com.vksql.network.proto";
option java_multiple_files = true;

service CoordinatorService {
    rpc SubmitQuery(QueryRequest) returns (QueryResult);
    rpc GetQueryStatus(QueryStatusRequest) returns (QueryStatus);
}

service WorkerService {
    rpc ExecuteStage(StageTask) returns (StageResult);
    rpc Heartbeat(HeartbeatRequest) returns (HeartbeatResponse);
}

message QueryRequest {
    string sql = 1;
}

message QueryResult {
    bool success = 1;
    string error = 2;
    repeated RecordBatch result_batches = 3;
}

message StageTask {
    int32 query_id = 1;
    int32 stage_id = 2;
    bytes serialized_plan = 3;  // serialized PhysicalOperator tree
    repeated PartitionAssignment inputs = 4;
}

message PartitionAssignment {
    int32 stage_id = 1;
    int32 partition_id = 2;
    string source_node = 3;  // host:port to pull shuffle data from
}

message StageResult {
    bool success = 1;
    string error = 2;
    int64 rows_produced = 3;
}

message HeartbeatRequest {
    string worker_id = 1;
    int64 timestamp = 2;
    repeated TaskProgress active_tasks = 3;
}

message HeartbeatResponse {
    bool acknowledged = 1;
}

message TaskProgress {
    int32 query_id = 1;
    int32 stage_id = 2;
    float progress = 3;  // 0.0 to 1.0
}

message QueryStatusRequest {
    int32 query_id = 1;
}

message QueryStatus {
    enum State { PLANNING = 0; RUNNING = 1; COMPLETED = 2; FAILED = 3; }
    State state = 1;
    int32 completed_stages = 2;
    int32 total_stages = 3;
}
```

---

## Step 3: Build the Coordinator

**File:** `network/src/main/java/com/vksql/network/coordinator/Coordinator.java`

```java
public class Coordinator {
    private final Parser parser;
    private final DistributedPlanner planner;
    private final PartitionManager partitionManager;
    private final Map<String, WorkerConnection> workers;  // nodeId → gRPC stub
    private final AtomicInteger queryIdGenerator = new AtomicInteger(0);

    public QueryResult executeQuery(String sql) {
        int queryId = queryIdGenerator.incrementAndGet();

        // 1. Parse SQL → LogicalPlan
        LogicalPlan logical = parser.parse(sql);

        // 2. Optimize → PhysicalPlan
        PhysicalOperator physical = optimizer.optimize(logical);

        // 3. Distribute → StageDAG
        StageDAG dag = planner.plan(physical);

        // 4. Execute stages in topological order
        Set<Integer> completed = new HashSet<>();
        for (Stage stage : dag.topologicalOrder()) {
            executeStage(queryId, stage, completed);
            completed.add(stage.stageId());
        }

        // 5. Collect results from final gather stage
        return collectResults(queryId, dag.getFinalStage());
    }
}
```

---

## Step 4: Stage Scheduling

The coordinator must respect dependencies. Stage 1 can't start until Stage 0 is done (because Stage 1 reads Stage 0's shuffle output).

```java
private void executeStage(int queryId, Stage stage, Set<Integer> completed) {
    // Determine which workers should run this stage
    List<String> targetWorkers = getWorkersForStage(stage);

    // Build task for each worker
    List<CompletableFuture<StageResult>> futures = new ArrayList<>();
    for (String workerId : targetWorkers) {
        StageTask task = buildTask(queryId, stage, workerId);
        futures.add(submitToWorker(workerId, task));
    }

    // Wait for ALL workers to complete this stage
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Check for failures
    for (var future : futures) {
        StageResult result = future.join();
        if (!result.getSuccess()) {
            throw new QueryExecutionException(result.getError());
        }
    }
}
```

**Parallel within a stage, sequential across stages.** All workers execute stage N in parallel, then all execute stage N+1 in parallel.

---

## Step 5: Build the Worker

**File:** `network/src/main/java/com/vksql/network/worker/Worker.java`

Each worker is a long-running process that:
- Listens for `ExecuteStage` RPCs
- Executes the physical plan fragment against local data
- Writes shuffle output for downstream stages
- Reports progress via heartbeats

```java
public class Worker {
    private final String workerId;
    private final Path localDataDir;          // partition files live here
    private final VectorizedExecutor executor;
    private final ShuffleServer shuffleServer;
    private final ShuffleWriter shuffleWriter;

    public StageResult executeStage(StageTask task) {
        // 1. Deserialize the physical plan
        PhysicalOperator plan = deserialize(task.getSerializedPlan());

        // 2. If plan has scan operators, bind them to local files
        bindScansToLocalData(plan, localDataDir);

        // 3. If plan reads shuffle input, connect to upstream nodes
        if (!task.getInputsList().isEmpty()) {
            connectShuffleInputs(plan, task.getInputsList());
        }

        // 4. Execute the plan using vectorized executor
        long rowsProduced = 0;
        VectorizedBatch batch;
        while ((batch = executor.next(plan)) != null) {
            if (plan.hasShuffleOutput()) {
                shuffleWriter.shuffleBatch(batch);
            } else {
                // Store locally for gather
                storeResult(task.getQueryId(), task.getStageId(), batch);
            }
            rowsProduced += batch.getRowCount();
        }

        return StageResult.newBuilder()
            .setSuccess(true)
            .setRowsProduced(rowsProduced)
            .build();
    }
}
```

---

## Step 6: Result Collection

The final stage has `ExchangeType.GATHER` — all workers send their results to the coordinator.

```java
private QueryResult collectResults(int queryId, Stage finalStage) {
    List<RecordBatch> allBatches = new ArrayList<>();

    // Pull from each worker's result buffer
    for (String workerId : getWorkersForStage(finalStage)) {
        var stub = workers.get(workerId).getShuffleStub();
        var request = ShuffleRequest.newBuilder()
            .setQueryId(queryId)
            .setStageId(finalStage.stageId())
            .setPartitionId(0)  // gather → single partition
            .build();

        Iterator<RecordBatch> batches = stub.receiveShuffle(request);
        batches.forEachRemaining(allBatches::add);
    }

    return QueryResult.newBuilder()
        .setSuccess(true)
        .addAllResultBatches(allBatches)
        .build();
}
```

---

## Step 7: Wire It All Up

**File:** `network/src/main/java/com/vksql/network/VksqlNode.java`

A convenience class that starts everything for a single node:

```java
public class VksqlNode {
    public static void main(String[] args) {
        String role = args[0];  // "coordinator" or "worker"
        int port = Integer.parseInt(args[1]);

        if ("coordinator".equals(role)) {
            new Coordinator(port).start();
        } else {
            String workerId = args[2];
            Path dataDir = Path.of(args[3]);
            new Worker(workerId, port, dataDir).start();
        }
    }
}
```

**Running a 3-node cluster locally:**
```bash
# Terminal 1 — Coordinator
java -jar vksql.jar coordinator 8080

# Terminal 2 — Worker 0
java -jar vksql.jar worker 9001 worker-0 data/partition_0

# Terminal 3 — Worker 1
java -jar vksql.jar worker 9002 worker-1 data/partition_1
```

---

## Step 8: Write Tests

```java
@Test
void endToEndDistributedQuery() throws Exception {
    // 1. Start coordinator + 2 workers (in-process)
    // 2. Load test data partitioned across workers
    // 3. Submit: SELECT SUM(amount) FROM orders GROUP BY customer_id
    // 4. Verify results match single-node execution
}

@Test
void stageExecutionRespectsDAGOrder() {
    // Verify stage 1 doesn't start until stage 0 completes
}
```

---

## Common Mistakes

1. **Coordinator doing data-heavy work** — the coordinator should never scan tables or compute aggregates. It only plans and schedules. If you find the coordinator processing data, you have a design bug.
2. **Not waiting for all workers in a stage** — if worker 2 is slow, you must wait for it before starting the next stage. Otherwise downstream stages read incomplete shuffle data.
3. **Serializing the plan as Java serialization** — use protobuf or JSON. Java serialization is fragile across versions.
4. **Hardcoding node addresses** — use the `PartitionManager` registry. Nodes should register on startup.
5. **Single-threaded coordinator** — the coordinator must handle multiple concurrent queries. Use a thread pool for query execution.

---

---

# Week 14: Fault Tolerance

## What You're Building

A system that detects worker failures and retries failed tasks, so a single node crash doesn't kill a 10-minute query.

---

## Step 1: Understand Failure Modes

| Failure | Detection | Recovery |
|---------|-----------|----------|
| Worker crash | Heartbeat timeout | Retry task on another worker |
| Network partition | gRPC deadline exceeded | Retry with exponential backoff |
| OOM on worker | Task returns error | Retry on different worker (or with smaller batch size) |
| Coordinator crash | Client gets connection refused | Client retries (coordinator is stateless for planning) |

For now, focus on **worker failures** — the most common case.

---

## Step 2: Heartbeat-Based Failure Detection

Each worker sends heartbeats to the coordinator every 5 seconds. If the coordinator doesn't hear from a worker for 15 seconds (3 missed heartbeats), it marks that worker as dead.

**File:** `network/src/main/java/com/vksql/network/coordinator/HeartbeatMonitor.java`

```java
public class HeartbeatMonitor {
    private final Map<String, Instant> lastHeartbeat = new ConcurrentHashMap<>();
    private final Duration timeout = Duration.ofSeconds(15);
    private final ScheduledExecutorService checker = Executors.newSingleThreadScheduledExecutor();
    private final Consumer<String> onWorkerDead;

    public HeartbeatMonitor(Consumer<String> onWorkerDead) {
        this.onWorkerDead = onWorkerDead;
        checker.scheduleAtFixedRate(this::checkHeartbeats, 5, 5, TimeUnit.SECONDS);
    }

    public void recordHeartbeat(String workerId) {
        lastHeartbeat.put(workerId, Instant.now());
    }

    private void checkHeartbeats() {
        Instant now = Instant.now();
        lastHeartbeat.forEach((workerId, lastSeen) -> {
            if (Duration.between(lastSeen, now).compareTo(timeout) > 0) {
                lastHeartbeat.remove(workerId);
                onWorkerDead.accept(workerId);  // Trigger failure handling
            }
        });
    }
}
```

**On the worker side:**
```java
// In Worker.java, start a heartbeat sender:
scheduler.scheduleAtFixedRate(() -> {
    coordinatorStub.heartbeat(HeartbeatRequest.newBuilder()
        .setWorkerId(workerId)
        .setTimestamp(System.currentTimeMillis())
        .build());
}, 0, 5, TimeUnit.SECONDS);
```

---

## Step 3: Task-Level Retry

When a worker dies mid-stage, you don't restart the entire query — you only retry the **tasks** that were assigned to the dead worker.

**File:** `network/src/main/java/com/vksql/network/coordinator/TaskRetryManager.java`

```java
public class TaskRetryManager {
    private final int maxRetries = 3;
    private final Map<TaskKey, Integer> attemptCounts = new ConcurrentHashMap<>();

    public record TaskKey(int queryId, int stageId, String workerId) {}

    /**
     * Called when a worker fails. Returns tasks that need to be rescheduled.
     */
    public List<StageTask> handleWorkerFailure(String deadWorkerId, List<StageTask> activeTasks) {
        List<StageTask> toRetry = new ArrayList<>();

        for (StageTask task : activeTasks) {
            TaskKey key = new TaskKey(task.getQueryId(), task.getStageId(), deadWorkerId);
            int attempts = attemptCounts.merge(key, 1, Integer::sum);

            if (attempts <= maxRetries) {
                toRetry.add(task);
            } else {
                throw new QueryFailedException(
                    "Task exceeded max retries: query=" + task.getQueryId() +
                    " stage=" + task.getStageId());
            }
        }
        return toRetry;
    }

    /**
     * Reassign tasks to healthy workers.
     */
    public Map<String, List<StageTask>> reassign(
            List<StageTask> tasks, List<String> healthyWorkers) {
        // Round-robin or least-loaded assignment
        Map<String, List<StageTask>> assignments = new HashMap<>();
        int i = 0;
        for (StageTask task : tasks) {
            String target = healthyWorkers.get(i % healthyWorkers.size());
            assignments.computeIfAbsent(target, k -> new ArrayList<>()).add(task);
            i++;
        }
        return assignments;
    }
}
```

---

## Step 4: Make Operators Idempotent

A retried task might have partially written shuffle data before crashing. If the task restarts and writes again, downstream stages could see duplicates.

**Solution: make every operator idempotent.** Running a task twice produces the same result as running it once.

Strategies:
1. **Unique attempt IDs** — each task attempt gets a unique ID. The shuffle receiver deduplicates by attempt ID.
2. **Write-ahead isolation** — shuffle output goes to a staging buffer. Only "committed" data becomes visible to downstream stages.
3. **Deterministic processing** — same input always produces same output (no random(), no time-dependent logic in operators).

```java
// Add attempt_id to StageTask:
message StageTask {
    int32 query_id = 1;
    int32 stage_id = 2;
    int32 attempt_id = 3;     // incremented on retry
    bytes serialized_plan = 4;
    repeated PartitionAssignment inputs = 5;
}
```

**In ShuffleServiceImpl, deduplicate:**
```java
// Key includes attempt_id — only the latest attempt's data is used
record ShuffleKey(int queryId, int stageId, int partitionId, int attemptId) {}

// When stage completes, coordinator tells receivers which attemptId is valid
// Receivers discard data from other attempts
```

---

## Step 5: Integrate Failure Handling into Coordinator

Update `Coordinator.executeStage()` to handle failures:

```java
private void executeStage(int queryId, Stage stage, Set<Integer> completed) {
    List<String> targetWorkers = getWorkersForStage(stage);
    Map<String, CompletableFuture<StageResult>> taskFutures = new HashMap<>();

    int attemptId = 0;
    for (String workerId : targetWorkers) {
        StageTask task = buildTask(queryId, stage, workerId, attemptId);
        taskFutures.put(workerId, submitToWorker(workerId, task));
    }

    // Wait with failure handling
    while (!taskFutures.isEmpty()) {
        for (var entry : new HashMap<>(taskFutures).entrySet()) {
            String workerId = entry.getKey();
            CompletableFuture<StageResult> future = entry.getValue();

            try {
                StageResult result = future.get(30, TimeUnit.SECONDS);
                if (result.getSuccess()) {
                    taskFutures.remove(workerId);
                } else {
                    // Task failed explicitly — retry
                    attemptId++;
                    String newWorker = pickHealthyWorker(workerId);
                    StageTask retryTask = buildTask(queryId, stage, newWorker, attemptId);
                    taskFutures.remove(workerId);
                    taskFutures.put(newWorker, submitToWorker(newWorker, retryTask));
                }
            } catch (TimeoutException e) {
                // Worker might be dead — check heartbeat
                if (!heartbeatMonitor.isAlive(workerId)) {
                    attemptId++;
                    String newWorker = pickHealthyWorker(workerId);
                    StageTask retryTask = buildTask(queryId, stage, newWorker, attemptId);
                    taskFutures.remove(workerId);
                    taskFutures.put(newWorker, submitToWorker(newWorker, retryTask));
                }
                // Otherwise keep waiting
            }
        }
    }
}
```

---

## Step 6: Exponential Backoff for Transient Failures

Not every failure is a dead worker. Network blips, temporary OOMs, and GC pauses cause transient errors that resolve on their own.

```java
public class RetryPolicy {
    private final int maxRetries;
    private final Duration initialBackoff;
    private final double backoffMultiplier;

    public Duration getBackoff(int attemptNumber) {
        long millis = (long) (initialBackoff.toMillis() * Math.pow(backoffMultiplier, attemptNumber));
        // Add jitter to prevent thundering herd
        long jitter = ThreadLocalRandom.current().nextLong(millis / 4);
        return Duration.ofMillis(millis + jitter);
    }
}

// Usage:
RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), 2.0);
// Attempt 0: wait ~1s
// Attempt 1: wait ~2s
// Attempt 2: wait ~4s
```

**Why jitter?** If all workers retry at the same time (e.g., after a coordinator blip), they stampede the recovering node. Random jitter spreads retries over time.

---

## Step 7: Stage-Level Restart vs Task-Level Retry

Sometimes task-level retry isn't enough. If the shuffle data from a completed stage was on a node that died, you need to **re-execute the entire upstream stage** to regenerate that data.

```
Stage 0 completes → shuffle data on worker-2
Stage 1 starts → worker-2 dies → shuffle data lost
→ Must re-run Stage 0 tasks that produced data on worker-2
```

This is expensive, so production systems (like Spark) write shuffle data to disk. If the node comes back, the data is still there.

For now, implement the simple version:
- If shuffle data is lost, mark the upstream stage as "needs re-execution"
- Re-execute only the partitions that were on the dead node

---

## Step 8: Write Tests

```java
@Test
void workerFailureRetriesToHealthyNode() {
    // Start 3 workers, submit a query
    // Kill worker-1 during stage execution
    // Verify the stage completes on remaining workers
}

@Test
void heartbeatTimeoutDetectsDeadWorker() {
    // Stop sending heartbeats from worker-2
    // Verify HeartbeatMonitor calls onWorkerDead within 15s
}

@Test
void idempotentRetryProducesCorrectResults() {
    // Execute a task that produces shuffle output
    // Simulate a failure after partial output
    // Retry the task
    // Verify downstream stage sees correct data (no duplicates)
}

@Test
void maxRetriesExceededFailsQuery() {
    // Set maxRetries = 2
    // Make a task always fail
    // Verify QueryFailedException after 2 retries
}

@Test
void exponentialBackoffIncreases() {
    var policy = new RetryPolicy(3, Duration.ofSeconds(1), 2.0);
    assertTrue(policy.getBackoff(1).toMillis() > policy.getBackoff(0).toMillis());
    assertTrue(policy.getBackoff(2).toMillis() > policy.getBackoff(1).toMillis());
}
```

---

## Common Mistakes

1. **Retrying the entire query instead of just the failed task** — massive waste. Only retry what failed.
2. **No max retry limit** — if a task is failing due to bad data (not a transient issue), infinite retries burn resources forever. Always cap retries.
3. **Non-idempotent operators** — if a retried aggregate task partially wrote results and then re-executes, you get double-counted values. Use attempt IDs to deduplicate.
4. **Heartbeat interval == timeout** — if heartbeat is every 5s and timeout is 5s, a single delayed heartbeat causes false detection. Use timeout = 3× heartbeat interval.
5. **Not draining in-flight RPCs on failure** — when you detect a dead worker, cancel all pending RPCs to that worker. Otherwise threads wait indefinitely.
6. **Thundering herd on retry** — without jitter, all retries hit the remaining workers simultaneously. Always add randomized jitter.

---

---

## Order of Implementation (All 5 Weeks)

| Week | Build | Depends On |
|------|-------|-----------|
| 10 | PartitionScheme, PartitionManager, DataLoader | Storage (Week 1-2) |
| 11 | ExchangeOperator, Stage, StageDAG, DistributedPlanner | Planner (Weeks 5-7) |
| 12 | shuffle.proto, ShuffleService, ShuffleWriter, BatchSerializer | Execution (Weeks 8-9) |
| 13 | coordinator.proto, Coordinator, Worker, VksqlNode | All of the above |
| 14 | HeartbeatMonitor, TaskRetryManager, idempotent ops | Week 13 |

---

## When You're Done With Weeks 10–14

- ✅ Data is hash-partitioned across N directories/nodes
- ✅ A single SQL query produces a multi-stage DAG with exchanges
- ✅ Shuffle moves data between nodes over gRPC with back-pressure
- ✅ Coordinator plans, schedules, and collects — never touches raw data
- ✅ Worker failures are detected within 15 seconds
- ✅ Failed tasks are retried on healthy nodes (up to max retries)
- ✅ Retried tasks produce correct results (idempotent/deduplicated)
- ✅ A TPC-H query runs correctly across 3+ nodes

**Next phase:** AI/ML extensions — vector search, HNSW index, similarity queries.
