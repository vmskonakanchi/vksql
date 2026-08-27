# Weeks 15–18: AI/ML Extensions

## What You're Building

A vector-native analytical engine — store embeddings as first-class columns, search them with approximate nearest neighbors (HNSW), serve features at sub-millisecond latency, and run batch inference with ONNX Runtime. By the end, you have an end-to-end RAG pipeline: embed → store → search → infer.

---

## Why This Matters

Modern AI workflows hit the database constantly:
- **Embedding storage** — every row in a semantic search system has a 768- or 1536-dim float vector
- **Similarity search** — find the 10 nearest neighbors to a query vector in milliseconds
- **Feature serving** — ML models need feature vectors at inference time with < 1ms latency
- **Batch inference** — score millions of rows through a model without leaving the database

Traditional databases bolt this on as an afterthought. You're building it into the storage and execution layers from day one.

---

# Week 15: Vector Column Type

## Concepts

A vector column stores fixed-dimensional float arrays. Every value in a `VECTOR(384)` column is exactly 384 floats = 1,536 bytes. This is fixed-width (like INT32 or FLOAT64) once you know the dimension — you get random access for free.

**Distance metrics:**
- **L2 (Euclidean):** `sqrt(Σ(a[i] - b[i])²)` — measures geometric distance
- **Cosine similarity:** `(a·b) / (|a|·|b|)` — measures angle between vectors (ignores magnitude)
- **Inner product:** `Σ(a[i] * b[i])` — cosine without normalization, faster

For KNN search: compute distance from query to every stored vector, keep top-K smallest. This is brute-force — O(n) per query — but correct and necessary as a baseline before adding indexes.

---

## Step 1: Add VECTOR to DataType

**File:** `storage/src/main/java/com/vksql/storage/format/DataType.java`

Add a `VECTOR` entry. Unlike other types, VECTOR is **parameterized** — it carries a dimension.

```java
public enum DataType {
    INT32(4),
    INT64(8),
    FLOAT64(8),
    STRING(-1),    // variable-length
    VECTOR(-1);    // parameterized: actual width = dimension * 4

    private final int fixedWidth;

    DataType(int fixedWidth) { this.fixedWidth = fixedWidth; }

    public int fixedWidth() { return fixedWidth; }
}
```

You'll also need a way to carry the dimension. Modify `ColumnDescriptor` to include an optional `dimension` field:

```java
public record ColumnDescriptor(String name, DataType type, int index, int dimension) {
    public ColumnDescriptor(String name, DataType type, int index) {
        this(name, type, index, 0); // non-vector types have dim=0
    }

    public int vectorByteWidth() {
        assert type == DataType.VECTOR;
        return dimension * Float.BYTES; // dim * 4
    }
}
```

---

## Step 2: Vector Storage Layout

Each vector value is exactly `dim * 4` bytes. Within a page:

```
[vector_0: dim * 4 bytes]  ← float[0], float[1], ..., float[dim-1]
[vector_1: dim * 4 bytes]
...
[vector_n: dim * 4 bytes]
```

No offsets needed — it's fixed-width! Random access to vector `i` is just `offset = i * dim * 4`.

**SIMD alignment:** Align each vector start to 32 bytes (AVX2) or 64 bytes (AVX-512). If `dim * 4` isn't a multiple of 32, pad each vector to the next 32-byte boundary.

```java
public int alignedVectorByteWidth(int dimension) {
    int raw = dimension * Float.BYTES;
    int alignment = 32; // AVX2
    return (raw + alignment - 1) & ~(alignment - 1); // round up to multiple of 32
}
```

**Syntax hint — writing aligned vectors with ByteBuffer:**
```java
int alignedWidth = alignedVectorByteWidth(dim);
ByteBuffer buf = ByteBuffer.allocate(alignedWidth * numVectors);
buf.order(ByteOrder.LITTLE_ENDIAN); // match hardware

for (float[] vec : vectors) {
    int start = buf.position();
    for (float f : vec) {
        buf.putFloat(f);
    }
    // pad to alignment
    buf.position(start + alignedWidth);
}
```

---

## Step 3: VectorPageWriter

**File:** `storage/src/main/java/com/vksql/storage/writer/VectorPageWriter.java`

A class that:
- Takes the vector dimension on construction
- Stores vectors into a `ByteBuffer` with alignment padding
- `isFull()` — checks if buffer capacity would be exceeded by the next vector
- `flush()` — returns a `Page` with the raw bytes

```java
public class VectorPageWriter {
    private final int dimension;
    private final int alignedWidth;
    private final ByteBuffer buffer;
    private int count;

    public VectorPageWriter(int dimension, int pageSize) {
        this.dimension = dimension;
        this.alignedWidth = alignedVectorByteWidth(dimension);
        this.buffer = ByteBuffer.allocate(pageSize);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public void writeVector(float[] vector) {
        assert vector.length == dimension;
        int start = buffer.position();
        for (float f : vector) {
            buffer.putFloat(f);
        }
        buffer.position(start + alignedWidth); // skip padding
        count++;
    }

    public boolean isFull() {
        return buffer.remaining() < alignedWidth;
    }

    public Page flush() {
        byte[] data = Arrays.copyOf(buffer.array(), buffer.position());
        buffer.clear();
        int flushed = count;
        count = 0;
        return new Page(flushed, data.length, data);
    }
}
```

---

## Step 4: Brute-Force KNN

**File:** `storage/src/main/java/com/vksql/storage/vector/DistanceFunction.java`

```java
public enum DistanceFunction {
    L2 {
        @Override
        public float compute(float[] a, float[] b) {
            float sum = 0;
            for (int i = 0; i < a.length; i++) {
                float diff = a[i] - b[i];
                sum += diff * diff;
            }
            return sum; // return squared L2 — avoids sqrt, preserves ordering
        }
    },
    COSINE {
        @Override
        public float compute(float[] a, float[] b) {
            float dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            return 1.0f - (dot / (float)(Math.sqrt(normA) * Math.sqrt(normB)));
        }
    };

    public abstract float compute(float[] a, float[] b);
}
```

**File:** `storage/src/main/java/com/vksql/storage/vector/BruteForceKnn.java`

```java
public class BruteForceKnn {
    public static int[] search(float[] query, float[][] vectors, int k, DistanceFunction distFn) {
        // Max-heap of size k: (distance, index)
        PriorityQueue<long> heap = new PriorityQueue<>(k, Comparator.reverseOrder());
        // Actually — use a record or encode both in a comparable wrapper:
        record Candidate(float distance, int index) implements Comparable<Candidate> {
            public int compareTo(Candidate o) {
                return Float.compare(o.distance, this.distance); // max-heap
            }
        }

        PriorityQueue<Candidate> heap = new PriorityQueue<>(k);
        for (int i = 0; i < vectors.length; i++) {
            float dist = distFn.compute(query, vectors[i]);
            if (heap.size() < k) {
                heap.offer(new Candidate(dist, i));
            } else if (dist < heap.peek().distance()) {
                heap.poll();
                heap.offer(new Candidate(dist, i));
            }
        }
        return heap.stream()
            .sorted(Comparator.comparingDouble(Candidate::distance))
            .mapToInt(Candidate::index)
            .toArray();
    }
}
```

---

## Step 5: Vectorized Distance (Loop Unrolling)

Java's Vector API (incubator in JDK 21) gives you SIMD without JNI:

**File:** `storage/src/main/java/com/vksql/storage/vector/SimdDistance.java`

```java
import jdk.incubator.vector.*;

public class SimdDistance {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_256; // AVX2: 8 floats

    public static float l2Squared(float[] a, float[] b) {
        int i = 0;
        FloatVector sumVec = FloatVector.zero(SPECIES);
        int bound = SPECIES.loopBound(a.length);

        for (; i < bound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            FloatVector diff = va.sub(vb);
            sumVec = diff.fma(diff, sumVec); // fused multiply-add: sum += diff * diff
        }

        float sum = sumVec.reduceLanes(VectorOperators.ADD);
        // Scalar tail
        for (; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
```

**Build config — enable incubator module:**
```kotlin
// In build.gradle.kts
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}
tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}
```

---

## Step 6: Read Vectors Back (VectorColumnReader)

**File:** `storage/src/main/java/com/vksql/storage/reader/VectorColumnReader.java`

Read pages and decode back to `float[][]`:

```java
public float[][] readAllVectors(RandomAccessFile raf, ColumnChunkMetadata meta, int dimension) {
    int alignedWidth = alignedVectorByteWidth(dimension);
    byte[] raw = new byte[(int) meta.totalSize()];
    raf.seek(meta.fileOffset());
    raf.readFully(raw);

    ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    int numVectors = (int) meta.numValues();
    float[][] result = new float[numVectors][dimension];

    for (int i = 0; i < numVectors; i++) {
        buf.position(i * alignedWidth);
        for (int d = 0; d < dimension; d++) {
            result[i][d] = buf.getFloat();
        }
    }
    return result;
}
```

---

## Step 7: Test It

```java
@Test
void vectorWriteReadRoundtrip() {
    var schema = new Schema(List.of(
        new ColumnDescriptor("id", DataType.INT32, 0),
        new ColumnDescriptor("embedding", DataType.VECTOR, 1, 128)
    ));
    // Write 10,000 random 128-dim vectors
    // Read them back
    // Verify float values are identical (use exact equality — no rounding should occur)
}

@Test
void bruteForceKnnCorrectness() {
    // Generate 1000 random vectors
    // Pick a query
    // Brute-force top-10
    // Verify by sorting all distances and checking top-10 manually
}

@Test
void simdMatchesScalar() {
    // Random vectors
    // Assert: SimdDistance.l2Squared(a, b) == DistanceFunction.L2.compute(a, b) within epsilon
}
```

---

## Order of Implementation (Week 15)

1. Add `VECTOR` to `DataType`, add `dimension` to `ColumnDescriptor`
2. Implement alignment utility method
3. Build `VectorPageWriter`
4. Integrate into `ColumnChunkWriter` (new `writeVector(float[])` path)
5. Update `RowGroupWriter` to handle VECTOR columns
6. Update footer serialization to persist dimension in schema
7. Build `VectorColumnReader`
8. Implement `DistanceFunction` (L2 + cosine, scalar)
9. Implement `BruteForceKnn`
10. Implement `SimdDistance` (Java Vector API)
11. Write tests — roundtrip, KNN correctness, SIMD vs scalar

---

## Common Mistakes (Week 15)

1. **Forgetting alignment padding** — if your vectors aren't aligned, SIMD loads will be slower or crash. Always pad to 32-byte boundaries.
2. **Using `Math.sqrt` in L2** — for ordering/ranking, squared L2 is sufficient and avoids an expensive sqrt per comparison.
3. **Float precision in tests** — don't use `assertEquals(expected, actual)` for floats. Use `assertEquals(expected, actual, 1e-6f)`.
4. **Not enabling the incubator module** — Vector API requires `--add-modules jdk.incubator.vector` at both compile time and runtime. JVM will throw `NoClassDefFoundError` if you forget.
5. **ByteOrder mismatch** — always use `LITTLE_ENDIAN` for vector data. Mixing byte orders between writer and reader silently corrupts values.

---

# Week 16: HNSW Index

## Concepts

Brute-force is O(n) — unusable for millions of vectors. **HNSW** (Hierarchical Navigable Small World) gives you approximate nearest neighbor search in O(log n) time with 95%+ recall.

**Key ideas:**
- A **multi-layer graph** — each layer is a random subset of nodes from the layer below
- **Layer 0** has all nodes. Layer 1 has ~1/M nodes. Layer 2 has ~1/M² nodes. Etc.
- **Search** starts at the top layer, greedily descends, and does a beam search at layer 0
- **Insertion** finds the entry point, connects the new node to its nearest neighbors at each layer

**Parameters:**
- `M` — max edges per node per layer (16 is typical). Higher = better recall, more memory
- `efConstruction` — beam width during insertion (200 typical). Higher = better graph quality, slower insert
- `efSearch` — beam width during query (50–200). Higher = better recall, slower search

**SQL syntax you're targeting:**
```sql
SELECT id, title
FROM documents
ORDER BY embedding <-> ?    -- <-> means "distance to parameter"
LIMIT 10;
```

---

## Step 1: Node and Graph Structures

**File:** `storage/src/main/java/com/vksql/storage/vector/hnsw/HnswNode.java`

```java
public class HnswNode {
    private final int id;
    private final float[] vector;
    private final int maxLayer;
    // Neighbors per layer: layer → list of neighbor IDs
    private final List<List<Integer>> connections;

    public HnswNode(int id, float[] vector, int maxLayer, int M) {
        this.id = id;
        this.vector = vector;
        this.maxLayer = maxLayer;
        this.connections = new ArrayList<>(maxLayer + 1);
        for (int l = 0; l <= maxLayer; l++) {
            connections.add(new ArrayList<>(l == 0 ? 2 * M : M));
        }
    }

    public List<Integer> getConnections(int layer) { return connections.get(layer); }
    public float[] vector() { return vector; }
    public int id() { return id; }
    public int maxLayer() { return maxLayer; }
}
```

---

## Step 2: Layer Assignment

Each new node is assigned to a random layer using a log-distribution:

```java
private int randomLayer(double mL) {
    return (int) (-Math.log(ThreadLocalRandom.current().nextDouble()) * mL);
}
// mL = 1.0 / Math.log(M)
```

This ensures exponential decay: most nodes are on layer 0, few on high layers.

---

## Step 3: Greedy Search (Single Layer)

The core algorithm — find the closest nodes on a given layer:

**File:** `storage/src/main/java/com/vksql/storage/vector/hnsw/HnswIndex.java`

```java
/**
 * Search a single layer starting from entry points.
 * Returns the ef closest nodes found.
 */
private PriorityQueue<Candidate> searchLayer(float[] query, List<Integer> entryPoints,
                                              int ef, int layer) {
    // Min-heap for results (closest first)
    PriorityQueue<Candidate> candidates = new PriorityQueue<>(Comparator.comparingDouble(Candidate::distance));
    // Max-heap for worst in result set
    PriorityQueue<Candidate> results = new PriorityQueue<>(
        Comparator.comparingDouble(Candidate::distance).reversed());
    Set<Integer> visited = new HashSet<>();

    for (int ep : entryPoints) {
        float dist = distanceFunction.compute(query, nodes.get(ep).vector());
        candidates.offer(new Candidate(dist, ep));
        results.offer(new Candidate(dist, ep));
        visited.add(ep);
    }

    while (!candidates.isEmpty()) {
        Candidate closest = candidates.poll();
        Candidate farthestResult = results.peek();

        if (closest.distance() > farthestResult.distance()) {
            break; // all remaining candidates are farther than our worst result
        }

        for (int neighborId : nodes.get(closest.index()).getConnections(layer)) {
            if (visited.add(neighborId)) {
                float dist = distanceFunction.compute(query, nodes.get(neighborId).vector());
                if (results.size() < ef || dist < results.peek().distance()) {
                    candidates.offer(new Candidate(dist, neighborId));
                    results.offer(new Candidate(dist, neighborId));
                    if (results.size() > ef) {
                        results.poll(); // remove farthest
                    }
                }
            }
        }
    }
    return results;
}
```

---

## Step 4: Insertion Algorithm

```java
public void insert(int id, float[] vector) {
    int nodeLayer = randomLayer(mL);
    HnswNode newNode = new HnswNode(id, vector, nodeLayer, M);
    nodes.put(id, newNode);

    if (entryPoint == -1) {
        entryPoint = id;
        maxLevel = nodeLayer;
        return;
    }

    List<Integer> currentEntryPoints = List.of(entryPoint);

    // Phase 1: Descend from top to nodeLayer+1 — greedy (ef=1)
    for (int layer = maxLevel; layer > nodeLayer; layer--) {
        var results = searchLayer(vector, currentEntryPoints, 1, layer);
        currentEntryPoints = List.of(results.peek().index());
    }

    // Phase 2: Search and connect at each layer from nodeLayer down to 0
    for (int layer = Math.min(nodeLayer, maxLevel); layer >= 0; layer--) {
        var results = searchLayer(vector, currentEntryPoints, efConstruction, layer);
        List<Candidate> neighbors = selectNeighbors(results, layer == 0 ? 2 * M : M);

        // Add bidirectional edges
        for (Candidate neighbor : neighbors) {
            newNode.getConnections(layer).add(neighbor.index());
            HnswNode neighborNode = nodes.get(neighbor.index());
            neighborNode.getConnections(layer).add(id);

            // Prune if over capacity
            int maxConn = (layer == 0) ? 2 * M : M;
            if (neighborNode.getConnections(layer).size() > maxConn) {
                pruneConnections(neighborNode, layer, maxConn);
            }
        }
        currentEntryPoints = neighbors.stream().map(c -> c.index()).toList();
    }

    // Update entry point if new node is on a higher layer
    if (nodeLayer > maxLevel) {
        entryPoint = id;
        maxLevel = nodeLayer;
    }
}
```

---

## Step 5: Neighbor Selection (Simple or Heuristic)

Two strategies:

**Simple:** Take the closest M neighbors.
```java
private List<Candidate> selectNeighbors(PriorityQueue<Candidate> candidates, int M) {
    return candidates.stream()
        .sorted(Comparator.comparingDouble(Candidate::distance))
        .limit(M)
        .toList();
}
```

**Heuristic (better recall):** Prefer diverse neighbors — don't add a neighbor if it's closer to an already-selected neighbor than to the query. This avoids clustering all edges in one direction.

Start with simple. Add heuristic later when tuning recall.

---

## Step 6: Search (Query Time)

```java
public List<Candidate> search(float[] query, int k, int efSearch) {
    List<Integer> currentEntryPoints = List.of(entryPoint);

    // Descend layers greedily
    for (int layer = maxLevel; layer > 0; layer--) {
        var results = searchLayer(query, currentEntryPoints, 1, layer);
        currentEntryPoints = List.of(results.peek().index());
    }

    // Search bottom layer with full beam width
    var results = searchLayer(query, currentEntryPoints, Math.max(efSearch, k), 0);

    // Return top-k from results
    return results.stream()
        .sorted(Comparator.comparingDouble(Candidate::distance))
        .limit(k)
        .toList();
}
```

---

## Step 7: Pruning Connections

When a node has too many edges, prune the farthest ones:

```java
private void pruneConnections(HnswNode node, int layer, int maxConn) {
    List<Integer> connections = node.getConnections(layer);
    float[] nodeVec = node.vector();

    List<Candidate> scored = connections.stream()
        .map(id -> new Candidate(distanceFunction.compute(nodeVec, nodes.get(id).vector()), id))
        .sorted(Comparator.comparingDouble(Candidate::distance))
        .limit(maxConn)
        .toList();

    connections.clear();
    scored.forEach(c -> connections.add(c.index()));
}
```

---

## Step 8: Persistence — Serialize HNSW to Disk

Store the graph alongside the column data in the file:

```
[HNSW Index Section]
  [Header: M, efConstruction, maxLevel, entryPoint, numNodes]
  [Node 0: layer, [connections per layer]]
  [Node 1: ...]
  ...
```

Vectors themselves live in the column chunk — don't duplicate them. The index only stores the graph edges (node IDs).

**Syntax hint — writing edges:**
```java
var dos = new DataOutputStream(out);
dos.writeInt(M);
dos.writeInt(efConstruction);
dos.writeInt(maxLevel);
dos.writeInt(entryPoint);
dos.writeInt(nodes.size());

for (HnswNode node : nodes.values()) {
    dos.writeInt(node.id());
    dos.writeInt(node.maxLayer());
    for (int layer = 0; layer <= node.maxLayer(); layer++) {
        List<Integer> conns = node.getConnections(layer);
        dos.writeInt(conns.size());
        for (int neighbor : conns) {
            dos.writeInt(neighbor);
        }
    }
}
```

---

## Step 9: Test Recall

```java
@Test
void hnswRecallAt10() {
    int n = 10_000, dim = 128, k = 10;
    float[][] vectors = randomVectors(n, dim);

    HnswIndex index = new HnswIndex(dim, M=16, efConstruction=200, DistanceFunction.L2);
    for (int i = 0; i < n; i++) {
        index.insert(i, vectors[i]);
    }

    // Query
    float[] query = randomVector(dim);
    List<Candidate> hnswResult = index.search(query, k, /* efSearch= */ 100);

    // Ground truth via brute-force
    int[] bruteForce = BruteForceKnn.search(query, vectors, k, DistanceFunction.L2);

    // Recall = |intersection| / k
    Set<Integer> hnswIds = hnswResult.stream().map(Candidate::index).collect(Collectors.toSet());
    Set<Integer> truthIds = IntStream.of(bruteForce).boxed().collect(Collectors.toSet());
    double recall = (double) Sets.intersection(hnswIds, truthIds).size() / k;

    assertTrue(recall >= 0.90, "Recall should be >= 90%, got " + recall);
}
```

---

## Order of Implementation (Week 16)

1. `HnswNode` — node with vector + connections per layer
2. `Candidate` record — (distance, index) pair
3. `randomLayer()` — log-distribution layer assignment
4. `searchLayer()` — beam search within one layer
5. `selectNeighbors()` — simple M-closest selection
6. `insert()` — full insertion algorithm
7. `search()` — multi-layer descent + bottom search
8. `pruneConnections()` — edge limit enforcement
9. Recall test against brute-force ground truth
10. Persistence — serialize/deserialize the graph
11. Integration with file format (index section in footer metadata)

---

## Common Mistakes (Week 16)

1. **Bidirectional edges** — when you connect A→B, you must ALSO connect B→A. Forgetting this halves your graph connectivity and destroys recall.
2. **Not pruning** — if you never prune, some nodes accumulate hundreds of edges. Memory explodes and search slows down (too many neighbors to visit).
3. **Using efSearch < k** — the beam width must be >= k, otherwise you can't even return k results. Always set `efSearch = max(efSearch, k)`.
4. **Wrong heap direction** — the candidates queue must be a min-heap (process closest first). The results queue must be a max-heap (quickly find/remove the farthest result). Swapping these makes the search diverge.
5. **Integer overflow in layer calculation** — `ThreadLocalRandom.nextDouble()` can return 0.0, and `Math.log(0)` = -∞. Guard against it: `Math.max(0, randomLayer(...))`.
6. **Testing with too few vectors** — HNSW needs hundreds of vectors minimum to form meaningful layer structure. Test with >= 1,000.

---

# Week 17: Feature Serving

## Concepts

ML inference needs feature vectors at prediction time. The model says "give me features for user_id=12345" — and you need to return a float array in under 1 millisecond.

This is a **point lookup** problem, not a scan. You need:
1. An index on the primary key to locate the row group + offset
2. An in-memory cache for hot features (LRU eviction)
3. Direct I/O to the vector column — skip all other columns

---

## Step 1: Primary Key Index

**File:** `storage/src/main/java/com/vksql/storage/index/PrimaryKeyIndex.java`

A mapping from primary key → (row group index, row offset within group).

```java
public class PrimaryKeyIndex {
    // key → (rowGroupIdx, rowOffset)
    private final Map<Long, long> index; // pack both into one long for memory efficiency

    public static long pack(int rowGroupIdx, int rowOffset) {
        return ((long) rowGroupIdx << 32) | (rowOffset & 0xFFFFFFFFL);
    }

    public static int rowGroupIdx(long packed) { return (int)(packed >>> 32); }
    public static int rowOffset(long packed) { return (int) packed; }
}
```

**Build the index at file open time** — scan row group metadata's min/max stats on the primary key column. For exact lookup, you need a finer-grained index.

---

## Step 2: Row Group Index on Primary Key

Two strategies:

**Strategy A: Sorted primary keys + binary search.** If data is written sorted by PK, each row group's min/max tells you which group contains a given key. Binary search over row groups, then binary search within the group.

**Strategy B: Hash index built at write time.** Store a hash map (key → file offset) as a separate section in the file.

Start with **Strategy A** — it requires the data to be sorted by PK at write time (add a sort step to the writer).

```java
public int findRowGroup(long primaryKey, List<RowGroupMetadata> rowGroups, int pkColumnIdx) {
    for (int i = 0; i < rowGroups.size(); i++) {
        ColumnChunkMetadata pkMeta = rowGroups.get(i).columns().get(pkColumnIdx);
        if (primaryKey >= pkMeta.min() && primaryKey <= pkMeta.max()) {
            return i;
        }
    }
    return -1; // not found
}
```

Within a row group, binary search the sorted PK column page-by-page using page-level min/max, then scan within the page.

---

## Step 3: Point Lookup Implementation

**File:** `storage/src/main/java/com/vksql/storage/serving/FeatureServer.java`

```java
public class FeatureServer {
    private final RandomAccessFile raf;
    private final FileFooter footer;
    private final PrimaryKeyIndex pkIndex;
    private final LRUCache<Long, float[]> cache;
    private final int vectorColumnIdx;
    private final int dimension;

    public float[] getFeature(long primaryKey) {
        // 1. Check cache
        float[] cached = cache.get(primaryKey);
        if (cached != null) return cached;

        // 2. Locate row group and offset
        int rowGroupIdx = pkIndex.findRowGroup(primaryKey);
        int rowOffset = pkIndex.findRowOffset(primaryKey, rowGroupIdx);
        if (rowOffset < 0) return null;

        // 3. Seek directly to the vector value
        ColumnChunkMetadata vecMeta = footer.rowGroups().get(rowGroupIdx)
            .columns().get(vectorColumnIdx);
        int alignedWidth = alignedVectorByteWidth(dimension);
        long seekPos = vecMeta.fileOffset() + (long) rowOffset * alignedWidth;

        // 4. Read exactly dim*4 bytes
        byte[] raw = new byte[dimension * Float.BYTES];
        raf.seek(seekPos);
        raf.readFully(raw);

        float[] vector = decodeVector(raw, dimension);

        // 5. Cache and return
        cache.put(primaryKey, vector);
        return vector;
    }
}
```

---

## Step 4: LRU Cache

**File:** `storage/src/main/java/com/vksql/storage/serving/LRUCache.java`

Use `LinkedHashMap` with access-order eviction:

```java
public class LRUCache<K, V> {
    private final LinkedHashMap<K, V> map;
    private final int maxSize;

    public LRUCache(int maxSize) {
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(maxSize, 0.75f, true) { // true = access-order
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }

    public synchronized V get(K key) { return map.get(key); }
    public synchronized void put(K key, V value) { map.put(key, value); }
}
```

**Sizing:** For 128-dim vectors, each entry is ~512 bytes + object overhead. A 100K-entry cache uses ~60MB. Size to fit your JVM heap.

---

## Step 5: Page-Level Min/Max for Primary Key

Extend page metadata to include per-page min/max for the primary key column. This allows skipping pages during within-row-group search.

```java
public record PageMetadata(long offset, int numValues, long minPk, long maxPk) {}
```

Binary search over pages:
```java
int pageIdx = Collections.binarySearch(pageMetadatas, targetKey,
    (page, key) -> {
        if (key < page.minPk()) return 1;
        if (key > page.maxPk()) return -1;
        return 0;
    });
```

---

## Step 6: Benchmark Latency

```java
@Test
void featureServingLatency() {
    // Write 1M rows sorted by PK
    // Open FeatureServer
    // Warm cache with 1000 random lookups
    // Time 10,000 lookups
    long start = System.nanoTime();
    for (int i = 0; i < 10_000; i++) {
        server.getFeature(randomKeys[i]);
    }
    long elapsed = System.nanoTime() - start;
    double p99 = ... ; // track per-lookup times in an array, sort, take index 9900
    assertTrue(p99 < 1_000_000, "p99 should be < 1ms, got " + p99 + "ns");
}
```

---

## Order of Implementation (Week 17)

1. Ensure writer outputs rows sorted by primary key
2. Add page-level min/max metadata to footer
3. Build `PrimaryKeyIndex` — binary search over row group min/max
4. Build within-row-group lookup — page-level binary search + scan
5. Build `LRUCache`
6. Build `FeatureServer` — combines index + cache + direct I/O
7. Benchmark: p99 < 1ms for 1M rows

---

## Common Mistakes (Week 17)

1. **Data not sorted** — binary search only works on sorted data. If the writer doesn't sort by PK, you must either sort at write time or fall back to a hash index.
2. **Reading too much data** — don't read the entire column chunk for a point lookup. Seek directly to `offset + rowIndex * valueWidth`.
3. **Cache size unbounded** — without eviction, you'll OOM. Always set a max size.
4. **Synchronization on cache** — `LinkedHashMap` is not thread-safe. Either synchronize access or use `ConcurrentLinkedHashMap` (Caffeine library provides one).
5. **Forgetting to account for alignment padding** — the seek offset calculation must use `alignedWidth`, not `dimension * 4`, if the writer used alignment.

---

# Week 18: Batch Inference

## Concepts

You have embeddings stored in your database. Now run them through an ML model **in bulk** — score millions of rows without extracting data to Python. The pipeline:

```
Scan column → Marshal float[] to ONNX tensor → Run model → Unmarshal output → Store results
```

**ONNX Runtime** is the standard for cross-framework model inference. It supports models exported from PyTorch, TensorFlow, scikit-learn, and runs in Java via `onnxruntime` bindings.

**RAG (Retrieval-Augmented Generation) demo flow:**
1. Store document embeddings in a VECTOR column
2. User asks a question → embed the question
3. HNSW search → find top-10 most similar documents
4. Feed those documents + question into an LLM (via ONNX or API call)

---

## Step 1: Add ONNX Runtime Dependency

**File:** `storage/build.gradle.kts`

```kotlin
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
    // For GPU support (optional):
    // implementation("com.microsoft.onnxruntime:onnxruntime_gpu:1.17.0")
}
```

---

## Step 2: Model Loading

**File:** `storage/src/main/java/com/vksql/storage/inference/OnnxModel.java`

```java
import ai.onnxruntime.*;

public class OnnxModel implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final long[] inputShape; // e.g., [-1, 128] where -1 = batch dim

    public OnnxModel(Path modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        // Enable optimization
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        this.session = env.createSession(modelPath.toString(), opts);

        // Inspect input metadata
        Map<String, NodeInfo> inputs = session.getInputInfo();
        var firstInput = inputs.entrySet().iterator().next();
        this.inputName = firstInput.getKey();
        TensorInfo tensorInfo = (TensorInfo) firstInput.getValue().getInfo();
        this.inputShape = tensorInfo.getShape();
    }

    public float[][] infer(float[][] batchInput) throws OrtException {
        long[] shape = new long[]{batchInput.length, batchInput[0].length};

        // Flatten 2D array to 1D for ONNX
        float[] flat = new float[batchInput.length * batchInput[0].length];
        for (int i = 0; i < batchInput.length; i++) {
            System.arraycopy(batchInput[i], 0, flat, i * batchInput[0].length, batchInput[0].length);
        }

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)) {
            Map<String, OnnxTensor> inputs = Map.of(inputName, inputTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] output = (float[][]) result.get(0).getValue();
                return output;
            }
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
        env.close();
    }
}
```

---

## Step 3: Batch Scan → Tensor Marshaling

**File:** `storage/src/main/java/com/vksql/storage/inference/BatchInferencePipeline.java`

```java
public class BatchInferencePipeline {
    private final OnnxModel model;
    private final int batchSize;

    public BatchInferencePipeline(OnnxModel model, int batchSize) {
        this.model = model;
        this.batchSize = batchSize; // e.g., 256 or 1024
    }

    /**
     * Scan vectors from a column reader, run inference in batches, return all outputs.
     */
    public float[][] runOnColumn(VectorColumnReader reader, ColumnChunkMetadata meta,
                                  RandomAccessFile raf, int dimension) throws Exception {
        float[][] allVectors = reader.readAllVectors(raf, meta, dimension);
        float[][] allOutputs = new float[allVectors.length][];

        for (int i = 0; i < allVectors.length; i += batchSize) {
            int end = Math.min(i + batchSize, allVectors.length);
            float[][] batch = Arrays.copyOfRange(allVectors, i, end);
            float[][] batchOutput = model.infer(batch);
            System.arraycopy(batchOutput, 0, allOutputs, i, batchOutput.length);
        }
        return allOutputs;
    }
}
```

---

## Step 4: Output Unmarshaling and Storage

After inference, write results back as a new column:

```java
public void inferAndStore(Path inputFile, Path outputFile, Path modelPath,
                          int vectorColumnIdx, String outputColumnName) throws Exception {
    try (var reader = new VksqlFileReader(inputFile);
         var model = new OnnxModel(modelPath)) {

        FileFooter footer = reader.getFooter();
        int dim = footer.schema().columns().get(vectorColumnIdx).dimension();

        // Determine output dimension from model
        float[][] sampleOut = model.infer(new float[][]{{new float[dim]}});
        int outputDim = sampleOut[0].length;

        // New schema: original + output column
        Schema newSchema = footer.schema().withColumn(
            new ColumnDescriptor(outputColumnName, DataType.VECTOR, footer.schema().columnCount(), outputDim)
        );

        try (var writer = new VksqlFileWriter(outputFile, newSchema)) {
            // Read each row group, infer, write combined output
            for (int rg = 0; rg < footer.rowGroups().size(); rg++) {
                float[][] vectors = readVectorsFromRowGroup(reader, rg, vectorColumnIdx, dim);
                float[][] outputs = model.infer(vectors); // may need batching

                // Write rows with original data + new output column
                // ... iterate and call writer.writeRow(...)
            }
        }
    }
}
```

---

## Step 5: End-to-End RAG Demo

**File:** `storage/src/main/java/com/vksql/storage/demo/RagDemo.java`

```java
public class RagDemo {
    public static void main(String[] args) throws Exception {
        // --- 1. Prepare data: documents with pre-computed embeddings ---
        Path dataFile = Path.of("rag_corpus.vksql");
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("doc_id", DataType.INT64, 0),
            new ColumnDescriptor("embedding", DataType.VECTOR, 1, 384)
            // In practice, you'd also store the document text
        ));

        try (var writer = new VksqlFileWriter(dataFile, schema)) {
            for (int i = 0; i < 100_000; i++) {
                float[] embedding = computeEmbedding(documents[i]); // pre-computed
                writer.writeRow((long) i, embedding);
            }
        }

        // --- 2. Build HNSW index ---
        var reader = new VksqlFileReader(dataFile);
        float[][] allEmbeddings = readAllVectors(reader, 1, 384);

        HnswIndex index = new HnswIndex(384, /*M=*/16, /*efConstruction=*/200, DistanceFunction.COSINE);
        for (int i = 0; i < allEmbeddings.length; i++) {
            index.insert(i, allEmbeddings[i]);
        }

        // --- 3. Query: find relevant documents ---
        String question = "How does HNSW handle deletions?";
        float[] queryEmbedding = embedQuestion(question); // via ONNX embedding model
        List<Candidate> topDocs = index.search(queryEmbedding, 10, /*efSearch=*/100);

        System.out.println("Top 10 relevant documents:");
        for (Candidate c : topDocs) {
            System.out.printf("  doc_id=%d, distance=%.4f%n", c.index(), c.distance());
        }

        // --- 4. (Optional) Feed to LLM for answer generation ---
        // String context = loadDocumentTexts(topDocs);
        // String answer = llm.generate(question, context);
    }
}
```

---

## Step 6: Embedding Model via ONNX

For the RAG demo, you need an embedding model. Export a sentence-transformers model to ONNX:

```bash
# Python (one-time export)
pip install optimum[onnxruntime]
optimum-cli export onnx --model sentence-transformers/all-MiniLM-L6-v2 ./minilm-onnx/
```

Then load in Java:
```java
OnnxModel embeddingModel = new OnnxModel(Path.of("minilm-onnx/model.onnx"));

// Tokenization: you'll need a tokenizer (or pre-tokenize in Python and pass token IDs)
// Simplification: pre-compute embeddings in Python, store float[] arrays in vkSQL
```

**Practical note:** Full tokenizer support in Java is complex. For the demo, pre-compute embeddings in Python and store the float arrays. The inference pipeline in Java works on the vectors directly (e.g., a classifier or dimensionality reduction model on top of embeddings).

---

## Step 7: Performance Tuning

Key knobs for batch inference throughput:

| Knob | What It Does | Typical Value |
|------|-------------|---------------|
| `batchSize` | Rows per inference call | 256–1024 |
| `intraOpNumThreads` | ONNX parallelism within one op | num CPUs |
| `interOpNumThreads` | ONNX parallelism across ops | 1 (avoid contention) |
| Page size | I/O granularity for column reads | 64KB–1MB |

```java
// Tuning ORT session
opts.setIntraOpNumThreads(8);
opts.setInterOpNumThreads(1);
opts.setOptimizationLevel(OptLevel.ALL_OPT);
// Optional: enable memory pattern optimization
opts.setMemoryPatternOptimization(true);
```

**Benchmark:**
```java
@Test
void batchInferenceThroughput() {
    // Load model, prepare 100K vectors
    long start = System.nanoTime();
    pipeline.runOnColumn(reader, meta, raf, dim);
    long elapsed = System.nanoTime() - start;
    double rowsPerSec = 100_000.0 / (elapsed / 1e9);
    System.out.printf("Throughput: %.0f rows/sec%n", rowsPerSec);
    // Target: > 10K rows/sec for a small model on CPU
}
```

---

## Order of Implementation (Week 18)

1. Add ONNX Runtime dependency
2. Build `OnnxModel` — load model, inspect input/output shapes
3. Build tensor marshaling — `float[][]` → `OnnxTensor`
4. Build `BatchInferencePipeline` — scan + batch + infer
5. Build output unmarshaling — model output → new column
6. Test with a simple ONNX model (e.g., linear layer or MLP)
7. Build RAG demo:
   - Pre-compute embeddings (Python helper script)
   - Store in vkSQL VECTOR column
   - Build HNSW index
   - Query → search → retrieve
8. Benchmark throughput

---

## Common Mistakes (Week 18)

1. **Tensor shape mismatch** — ONNX models expect specific input shapes (e.g., `[batch, 384]`). If you pass `[384]` (missing batch dimension), you get a cryptic error. Always include the batch dimension.
2. **Memory leaks with OnnxTensor** — tensors are native memory. Always use try-with-resources or explicitly close them. Leaking tensors will OOM your JVM.
3. **Batch size too large** — large batches use proportionally more memory. If your model is large, start with batch=64 and increase until you hit memory limits.
4. **Not flattening the input** — ONNX Java API expects a 1D `FloatBuffer` + shape, not a 2D array directly. You must flatten `float[][]` to `float[]` and specify the shape separately.
5. **Ignoring model warmup** — the first inference call is 10-100x slower (JIT compilation in ORT). Run 1-2 warmup batches before benchmarking.
6. **Thread safety** — `OrtSession` is thread-safe for concurrent `run()` calls, but `OrtEnvironment` should be shared (singleton). Don't create multiple environments.

---

## When You're Done (All 4 Weeks)

- ✅ VECTOR(dim) is a first-class column type with aligned storage
- ✅ Brute-force KNN works with L2 and cosine, vectorized with Java Vector API
- ✅ HNSW index builds and searches with ≥ 90% recall at 10K vectors
- ✅ Point lookups by primary key return features in < 1ms p99
- ✅ LRU cache evicts cold features, keeps hot ones in memory
- ✅ ONNX models load and run batch inference on stored vectors
- ✅ End-to-end RAG pipeline: store → index → search → retrieve
- ✅ All tests pass, benchmarks meet targets

**What you've built:** A storage engine that natively supports AI/ML workloads — not as a bolt-on extension, but integrated into the columnar format, the execution engine, and the query path. This is the foundation for `ORDER BY embedding <-> ? LIMIT 10` becoming a first-class SQL operation.
