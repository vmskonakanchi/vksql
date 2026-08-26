# Java Refresher — "Senior Engineer Back from a Coma"

You know Java. You wrote concurrent code, built servers, used generics. But you've been away.
This doc catches you up on everything that matters for building a data engine in 2026.

Format: what changed, what you forgot, what you never went deep on. No baby steps.

---

## 1. What's New Since You Were Gone (Java 17 → 21+)

### Records (Java 16+)
Immutable data carriers. No more boilerplate POJOs.
```java
record ColumnChunk(String name, int[] data, boolean[] nulls) {}
// Gives you: constructor, getters (name(), data(), nulls()), equals, hashCode, toString
// They're final. Can't extend. Can implement interfaces.
```
Use for: query plan nodes, schema definitions, intermediate results.

### Sealed Classes (Java 17+)
Restricted class hierarchies. The compiler knows all subtypes.
```java
sealed interface PlanNode permits Scan, Filter, Project, Join, Aggregate {
    List<PlanNode> children();
}
record Scan(String table, List<String> columns) implements PlanNode {
    public List<PlanNode> children() { return List.of(); }
}
record Filter(PlanNode child, Expression predicate) implements PlanNode {
    public List<PlanNode> children() { return List.of(child); }
}
```
Why it matters: exhaustive switch/pattern matching. Compiler catches missing cases.

### Pattern Matching (Java 21+)
```java
// Old
if (node instanceof Filter) {
    Filter f = (Filter) node;
    // use f
}

// New — binding + guards
switch (node) {
    case Scan s -> executeScan(s);
    case Filter(var child, var pred) when pred.isAlwaysTrue() -> execute(child);
    case Filter f -> executeFilter(f);
    case Join j -> executeJoin(j);
    // compiler error if you miss a permitted subtype
}
```

### Virtual Threads (Java 21+)
You used these in vkdb. Quick refresher on the sharp edges:
```java
// Creation — cheap, millions of them
Thread.startVirtualThread(() -> handleRequest(conn));

// Or via executor
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> processPartition(p));
}
```
**Pinning (the trap you need to know):**
- Virtual thread gets PINNED to a carrier thread inside `synchronized` blocks
- Pinned = defeats the purpose, blocks the carrier
- Fix: replace `synchronized` with `ReentrantLock`
```java
// BAD — pins virtual thread
synchronized (lock) { doIO(); }

// GOOD — virtual thread can unmount while waiting
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try { doIO(); } finally { lock.unlock(); }
```

### Structured Concurrency (Preview, Java 21+)
Fork-join but scoped. Parent waits for children. If one fails, others cancel.
```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<byte[]> partition1 = scope.fork(() -> readPartition(0));
    Subtask<byte[]> partition2 = scope.fork(() -> readPartition(1));
    Subtask<byte[]> partition3 = scope.fork(() -> readPartition(2));
    
    scope.join();           // wait for all
    scope.throwIfFailed();  // propagate first failure
    
    merge(partition1.get(), partition2.get(), partition3.get());
}
// All subtasks are GUARANTEED done (success or failure) when scope exits
```
Use for: parallel partition reads, distributed query stages, fan-out/fan-in patterns.

### Scoped Values (Preview, Java 21+)
Thread-local replacement for virtual threads. Immutable, inherited by child threads.
```java
static final ScopedValue<QueryContext> QUERY_CTX = ScopedValue.newInstance();

ScopedValue.runWhere(QUERY_CTX, new QueryContext(queryId, user), () -> {
    // all code here (including spawned virtual threads) sees QUERY_CTX
    executeQuery();
});
```

### Text Blocks, switch expressions, unnamed variables
```java
// Text blocks (Java 15+)
String sql = """
    SELECT col1, col2
    FROM table1
    WHERE col1 > 100
    """;

// Switch expressions
int size = switch (type) {
    case INT, FLOAT -> 4;
    case LONG, DOUBLE -> 8;
    case BOOLEAN -> 1;
    default -> throw new IllegalArgumentException();
};

// Unnamed variables (Java 22+) — when you don't need the value
try { parse(sql); } catch (ParseException _) { return ErrorResult.SYNTAX_ERROR; }
```

---

## 2. Memory Model & Concurrency (The Hard Stuff)

### Java Memory Model — What You Forgot

Every thread has a local cache. Writes may not be visible to other threads unless you establish a **happens-before** relationship.

Happens-before is established by:
- `volatile` write → subsequent `volatile` read of same variable
- `lock.unlock()` → subsequent `lock.lock()` of same lock
- `thread.start()` → first action in that thread
- `thread.join()` returns → calling thread sees all writes from joined thread

```java
// BROKEN — reader may never see stop = true
boolean stop = false; // shared

// Thread 1
stop = true;

// Thread 2 (may loop forever)
while (!stop) { work(); }

// FIXED
volatile boolean stop = false;
```

**For your query engine:** when a coordinator writes partition assignments and workers read them, you NEED happens-before. Use volatile flags, concurrent collections, or explicit synchronization.

### Lock Hierarchy — When to Use What

| Lock | Use When |
|------|----------|
| `synchronized` | Simple mutual exclusion, NO virtual threads doing I/O inside |
| `ReentrantLock` | Need tryLock, timed lock, or use with virtual threads |
| `ReadWriteLock` | Many readers, few writers (catalog metadata, schema cache) |
| `StampedLock` | Hot read path, optimistic reads acceptable (buffer pool lookups) |
| No lock (CAS) | Single-variable updates, counters, state flags |

```java
// StampedLock — optimistic read (no blocking if no writer)
private final StampedLock sl = new StampedLock();
private double[] buffer;

double readFromBuffer(int idx) {
    long stamp = sl.tryOptimisticRead();    // non-blocking
    double val = buffer[idx];
    if (!sl.validate(stamp)) {              // writer interfered?
        stamp = sl.readLock();              // fall back to real read lock
        try { val = buffer[idx]; }
        finally { sl.unlockRead(stamp); }
    }
    return val;
}
```

### Lock-Free / CAS

```java
// AtomicReference for state machines (query state transitions)
enum QueryState { PLANNING, EXECUTING, FINISHED, FAILED }
AtomicReference<QueryState> state = new AtomicReference<>(QueryState.PLANNING);

// Only one thread can transition PLANNING → EXECUTING
boolean started = state.compareAndSet(QueryState.PLANNING, QueryState.EXECUTING);

// AtomicLong for stats counters (no lock needed)
AtomicLong rowsProcessed = new AtomicLong(0);
rowsProcessed.addAndGet(batchSize); // lock-free increment
```

### VarHandle (Java 9+) — Low-level atomic operations
```java
// When AtomicXxx classes have too much overhead (hot inner loops)
private int count;
private static final VarHandle COUNT;
static {
    COUNT = MethodHandles.lookup().findVarHandle(MyClass.class, "count", int.class);
}
// CAS directly on a field — no wrapper object
COUNT.compareAndSet(this, expected, newValue);
COUNT.getOpaque(this); // relaxed read (no ordering guarantees, but atomic)
```

### ForkJoinPool — Parallel Computation
```java
// For CPU-bound parallel work (scanning partitions, hash builds)
ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

// RecursiveTask — divide and conquer
class SumTask extends RecursiveTask<Long> {
    final int[] data; final int lo, hi;
    
    protected Long compute() {
        if (hi - lo < 10_000) {
            long sum = 0;
            for (int i = lo; i < hi; i++) sum += data[i];
            return sum;
        }
        int mid = (lo + hi) / 2;
        SumTask left = new SumTask(data, lo, mid);
        SumTask right = new SumTask(data, mid, hi);
        left.fork();           // submit left to pool
        long r = right.compute(); // compute right in current thread
        return left.join() + r;   // wait for left
    }
}
```

---

## 3. NIO & High-Performance I/O

### ByteBuffer — The Fundamental Primitive

All high-performance I/O in Java goes through ByteBuffer.

```java
// Heap buffer — backed by byte[], subject to GC, can't do zero-copy
ByteBuffer heap = ByteBuffer.allocate(4096);

// Direct buffer — off-heap, OS can DMA directly, no GC movement
ByteBuffer direct = ByteBuffer.allocateDirect(4096);

// CRITICAL: position/limit/capacity model
// After writing: flip() before reading
// After reading: compact() or clear() before writing again
direct.putInt(42);
direct.putLong(123L);
direct.flip();  // position=0, limit=where you stopped writing
int val = direct.getInt();  // reads 42
```

**For your storage engine:** direct ByteBuffers for file I/O. Heap buffers for temporary computation.

### FileChannel — Random Access

```java
// Reading a specific row group from a columnar file
try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
    ByteBuffer buf = ByteBuffer.allocateDirect(rowGroupSize);
    ch.read(buf, offset); // positioned read — no seek needed, thread-safe
    buf.flip();
    // decode column chunk from buf
}
```

### Memory-Mapped Files — The Fastest Path

```java
// Map entire file into virtual memory. OS handles paging.
try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
    MappedByteBuffer mmap = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
    // Access like a regular ByteBuffer — OS pages in on demand
    int magic = mmap.getInt(0);
    // No explicit read() calls. No system calls for cached data.
}
```
When to use: read-heavy workloads, file fits in address space, OS page cache is warm.  
When NOT to use: files > 2GB on 32-bit (not a problem anymore), need explicit control over eviction.

### Zero-Copy

```java
// Transfer from file to socket without copying through user space
try (FileChannel file = FileChannel.open(path);
     SocketChannel socket = SocketChannel.open(address)) {
    file.transferTo(0, file.size(), socket); // OS does DMA → DMA
}
```

---

## 4. Off-Heap Memory — Panama Foreign Function & Memory API (Java 21+)

### Why Off-Heap?
Your query engine will hold large columnar buffers. If they're on-heap:
- GC scans them (wasted CPU)
- GC moves them (can't do zero-copy I/O)
- Heap size limits you (even with 64GB, GC pauses grow)

Solution: allocate off-heap, manage lifecycle yourself.

### Arena & MemorySegment

```java
// Confined arena — memory freed when arena closes, only usable by one thread
try (Arena arena = Arena.ofConfined()) {
    // Allocate 1MB off-heap
    MemorySegment segment = arena.allocate(1024 * 1024);
    
    // Type-safe access
    segment.setAtIndex(ValueLayout.JAVA_INT, 0, 42);
    segment.setAtIndex(ValueLayout.JAVA_INT, 1, 99);
    int val = segment.getAtIndex(ValueLayout.JAVA_INT, 0); // 42
    
    // Bulk copy from byte array
    MemorySegment.copy(sourceArray, 0, segment, ValueLayout.JAVA_BYTE, 0, sourceArray.length);
} // ALL memory freed here. Deterministic. No GC.

// Shared arena — usable by multiple threads
try (Arena arena = Arena.ofShared()) {
    MemorySegment shared = arena.allocate(4096);
    // Pass `shared` to worker threads safely
}

// Auto arena — GC cleans up (like DirectByteBuffer but better API)
Arena auto = Arena.ofAuto();
MemorySegment seg = auto.allocate(4096);
// freed when GC collects the arena — non-deterministic, use sparingly
```

### Mapping Files with Panama

```java
try (Arena arena = Arena.ofConfined();
     FileChannel ch = FileChannel.open(path)) {
    MemorySegment mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
    // Access via MemorySegment API — bounds-checked, safer than MappedByteBuffer
    int header = mapped.get(ValueLayout.JAVA_INT, 0);
}
```

### Buffer Pool Pattern (How Real Engines Do It)
```java
class BufferPool {
    private final Arena arena = Arena.ofShared();
    private final int pageSize;
    private final Queue<MemorySegment> freePages = new ConcurrentLinkedQueue<>();
    
    MemorySegment acquire() {
        MemorySegment page = freePages.poll();
        if (page == null) page = arena.allocate(pageSize);
        return page;
    }
    
    void release(MemorySegment page) {
        page.fill((byte) 0); // zero out for safety
        freePages.offer(page);
    }
}
```

---

## 5. Collections & Data Structures — Systems Edition

### What You Already Know (Skip)
ArrayList, HashMap, LinkedList, TreeMap, ConcurrentHashMap, BlockingQueue.

### What Matters for a Data Engine

**Raw arrays > everything else in hot paths:**
```java
// Column vector — this is how vectorized engines store data
int[] values = new int[1024];     // column of INT values
boolean[] nulls = new boolean[1024]; // null bitmap
int count = 0;                    // actual rows in this batch

// Tight loop — JIT will auto-vectorize this (SIMD)
long sum = 0;
for (int i = 0; i < count; i++) {
    if (!nulls[i]) sum += values[i];
}
```

**BitSet for null bitmaps and bloom filters:**
```java
// More compact than boolean[] for large datasets
BitSet nullBitmap = new BitSet(rowCount);
nullBitmap.set(5);     // row 5 is null
nullBitmap.get(5);     // true = null

// Bloom filter sketch (you'll implement a real one)
BitSet bloom = new BitSet(1024);
int hash1 = key.hashCode() & 1023;
int hash2 = (key.hashCode() >>> 16) & 1023;
bloom.set(hash1);
bloom.set(hash2);
// Check: if both bits set, MAYBE present. If any unset, DEFINITELY not present.
```

**IntStream / Arrays.parallelSort for bulk ops:**
```java
// Parallel sort for order-by operator
int[] column = getColumnData();
Arrays.parallelSort(column); // ForkJoinPool internally, ~3x faster on 8 cores

// Partitioning data by hash
int[] partitionIds = new int[rowCount];
Arrays.setAll(partitionIds, i -> Math.floorMod(keys[i].hashCode(), numPartitions));
```

**Open-addressing hash maps (not java.util.HashMap):**
```java
// java.util.HashMap uses chaining (linked list per bucket)
// For hash joins, you want open addressing — better cache locality
// You'll build your own. Key insight:
// - Flat array of keys + values
// - Linear probing or Robin Hood hashing
// - 10x faster than HashMap for primitive keys

// Eclipse Collections or Koloboke for off-the-shelf primitive maps:
// IntIntHashMap, LongObjectHashMap — no boxing
```

---

## 6. JVM Internals That Affect Your Engine

### Garbage Collectors — Pick One

| GC | Pause Target | Use When |
|----|-------------|----------|
| G1 | 200ms default | General workloads, moderate heap |
| ZGC | < 1ms | Large heaps (100GB+), latency-sensitive serving |
| Shenandoah | < 10ms | Similar to ZGC, different tradeoffs |

**For your query engine:**
- Development/testing: default (G1)
- Benchmarking batch execution: G1 with large young gen (`-Xmn`)
- Latency-sensitive serving layer (Phase 4): ZGC (`-XX:+UseZGC`)

**Best strategy:** minimize garbage in hot paths (object pooling, primitive arrays, off-heap). Then GC choice matters less.

### JIT — Write Code the JVM Can Optimize

```java
// 1. Monomorphic call sites (one implementation) — JVM inlines aggressively
interface Operator { RowBatch next(); }
// If at runtime there's only ONE class implementing Operator at a call site, JIT inlines it

// 2. Small methods get inlined (<325 bytecodes by default)
// Break big methods into small focused ones

// 3. Loop optimization — keep loops simple and bounds-checkable
for (int i = 0; i < array.length; i++) { // JIT eliminates bounds check
    array[i] = array[i] + 1;
}

// 4. Avoid megamorphic sites (>2 implementations at a call site)
// This KILLS inlining. In your execution engine, this matters!
// Solution: generated/specialized code for hot operators
```

### SIMD Auto-Vectorization

The JVM can auto-vectorize tight loops IF you write them correctly:
```java
// ✅ VECTORIZABLE — simple loop, no dependencies between iterations
void addColumns(int[] result, int[] a, int[] b, int count) {
    for (int i = 0; i < count; i++) {
        result[i] = a[i] + b[i];
    }
}

// ❌ NOT vectorizable — dependency between iterations
for (int i = 1; i < count; i++) {
    result[i] = result[i-1] + a[i]; // depends on previous iteration
}

// ❌ NOT vectorizable — method call in loop
for (int i = 0; i < count; i++) {
    result[i] = transform(a[i]); // unless transform is inlined
}
```

Verify with: `-XX:+PrintCompilation -XX:+TraceLoopOpts` or better, use JMH + async-profiler with `-prof perfasm` to see actual SIMD instructions.

### JMH — Microbenchmarking
```java
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2) // 2 JVM forks to avoid profile pollution
public class ScanBenchmark {
    
    @State(Scope.Thread)
    public static class Data {
        int[] column = new int[1_000_000];
        @Setup
        public void setup() { /* fill with random data */ }
    }
    
    @Benchmark
    public long sumColumn(Data d) {
        long sum = 0;
        for (int i = 0; i < d.column.length; i++) sum += d.column[i];
        return sum; // return result so JIT can't eliminate the loop
    }
}
// Run: java -jar benchmarks.jar ScanBenchmark -f 2 -wi 5 -i 10
```

---

## 7. Networking — gRPC in Java

### Why gRPC (not raw sockets like vkdb)
- Binary protocol (protobuf) — efficient serialization
- Streaming — send batches of rows as a stream
- Code generation — type-safe client/server from .proto
- HTTP/2 — multiplexing, flow control, built-in

### Core Pattern

```protobuf
// query_service.proto
service QueryWorker {
    rpc ExecuteStage (StageRequest) returns (stream RowBatch);
    rpc Heartbeat (HeartbeatRequest) returns (HeartbeatResponse);
    rpc Shuffle (stream RowBatch) returns (ShuffleAck);
}

message RowBatch {
    int32 row_count = 1;
    repeated ColumnData columns = 2;
}
message ColumnData {
    bytes values = 1;    // raw column bytes
    bytes null_bitmap = 2;
}
```

```java
// Server
Server server = ServerBuilder.forPort(9090)
    .addService(new QueryWorkerImpl())
    .build()
    .start();

// Client — server streaming (coordinator pulls results from worker)
Iterator<RowBatch> results = stub.executeStage(request);
while (results.hasNext()) {
    RowBatch batch = results.next();
    processBatch(batch);
}

// Client — client streaming (worker pushes shuffle data)
StreamObserver<RowBatch> sender = asyncStub.shuffle(responseObserver);
for (RowBatch batch : localResults) {
    sender.onNext(batch);
}
sender.onCompleted();
```

---

## 8. Build & Tooling

### Gradle (not Maven)

Maven is XML hell and slow. Gradle for iterative development:
```kotlin
// build.gradle.kts
plugins {
    java
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.62.2")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("org.github.jsqlparser:jsqlparser:4.9")
    implementation("org.xerial.snappy:snappy-java:1.1.10.5")
    implementation("com.github.luben:zstd-jni:1.5.5-11")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
```

### Running with the right JVM flags
```bash
# Development
java --enable-preview -ea -jar engine.jar

# Benchmarking
java -XX:+UseZGC -Xms4g -Xmx4g -XX:+AlwaysPreTouch \
     --add-modules jdk.incubator.vector \
     -jar engine.jar

# Profiling
java -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
     -jar engine.jar &
async-profiler -d 30 -f profile.html <pid>
```

---

## 9. Quick Reference — "I Forgot the Syntax"

### File I/O
```java
// Read all bytes
byte[] data = Files.readAllBytes(Path.of("file.bin"));

// Write bytes  
Files.write(Path.of("out.bin"), data);

// Streaming read (large files)
try (InputStream is = Files.newInputStream(path);
     BufferedInputStream bis = new BufferedInputStream(is, 64 * 1024)) {
    // read in chunks
}
```

### Functional / Streams (for non-hot paths)
```java
// DON'T use streams in hot execution paths (boxing, allocation, megamorphic calls)
// DO use for setup, configuration, test assertions

List<String> columnNames = schema.columns().stream()
    .filter(c -> c.type() == Type.INT)
    .map(Column::name)
    .toList(); // Java 16+ — immutable list
```

### Try-with-resources (you know this, but the multi-resource form)
```java
try (var arena = Arena.ofConfined();
     var channel = FileChannel.open(path);
     var connection = grpcChannel.newCall()) {
    // all three closed in reverse order on exit
}
```

### Var (Java 11+)
```java
// Use for local variables when the type is obvious from RHS
var map = new ConcurrentHashMap<String, ColumnChunk>();
var segment = arena.allocate(4096);

// DON'T use when type isn't obvious
var result = computeSomething(); // what type is this? reader can't tell
```

---

## 10. What to Read Next (In Order)

1. **Java Concurrency in Practice** (Goetz) — chapters 3, 12, 16. The memory model chapter alone is worth it.
2. **JVM source: `java.util.concurrent`** — read `ConcurrentHashMap`, `ForkJoinPool`, `StampedLock` source code. It's the best concurrent code you'll ever read.
3. **Panama API docs** — https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html
4. **Apache Arrow Java implementation** — study how they do off-heap columnar buffers: `arrow-memory-core`, `arrow-vector`
5. **Trino source code** — `trino-main/src/main/java/io/trino/operator/` — real vectorized operators in Java

---

## TL;DR — What Changed, What Matters

| Era | You Knew | Now Use |
|-----|----------|---------|
| Classes | POJOs with getters | Records |
| Instanceof | Cast after check | Pattern matching |
| Threads | `new Thread()` | Virtual threads + structured concurrency |
| Off-heap | `sun.misc.Unsafe` / DirectByteBuffer | Panama `MemorySegment` + `Arena` |
| Concurrency | `synchronized` | `ReentrantLock` (virtual-thread safe) |
| Switch | Statement with fallthrough | Expression with pattern matching |
| Thread-local | `ThreadLocal<T>` | `ScopedValue<T>` (with virtual threads) |
| Serialization | `Serializable` | Just don't. Use protobuf. |
| Build | Maven | Gradle Kotlin DSL |

Now go build the storage engine. Phase 1, Week 1. Start with the file format.
