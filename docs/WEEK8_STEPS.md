# Week 8: Vectorized Execution

## What You're Building

A vectorized execution engine that processes data **1024 rows at a time** using tight loops over primitive arrays — instead of the traditional Volcano model that calls `next()` one row at a time. By the end of this week, a full table scan + filter will be 5–10x faster than Volcano.

---

## Why Vectorized?

The Volcano model (iterator-based, one row at a time) has been the standard since the 1990s. It's elegant but slow for analytics:

```
Volcano:   Scan.next() → Filter.next() → Project.next()   [per row, virtual call overhead]
Vectorized: Scan.nextBatch() → Filter.eval(batch) → Project.eval(batch)  [1024 rows, tight loop]
```

Three reasons vectorized is faster:
1. **No virtual dispatch per row** — one virtual call per 1024 rows instead of per row
2. **Cache-friendly** — looping over a contiguous `int[]` keeps the CPU cache hot
3. **JIT auto-vectorization** — the JVM can use SIMD instructions (SSE/AVX) on simple loops over arrays

The academic paper that started this: "MonetDB/X100: Hyper-Pipelining Query Execution" (Boncz et al., 2005).

---

## Step 1: ColumnVector — The Core Abstraction

**File:** `execution/src/main/java/com/vksql/execution/vector/ColumnVector.java`

A `ColumnVector` wraps a primitive array plus a null bitmap. It represents one column's worth of data for a single batch (up to 1024 values).

**What it holds:**
- A primitive array (`int[]`, `long[]`, or `double[]`) — the actual values
- A `boolean[] nulls` — null bitmap (true = null at that index)
- `int size` — how many values are actually valid in this batch (≤ 1024)
- `DataType type` — what kind of data this column holds

**Design:**

```java
public class ColumnVector {
    public static final int DEFAULT_BATCH_SIZE = 1024;

    private final DataType type;
    private int size;

    // Only one of these is active, depending on type:
    private int[] intValues;
    private long[] longValues;
    private double[] doubleValues;

    // Null tracking
    private boolean[] nulls;
    private boolean hasNulls;

    public ColumnVector(DataType type) {
        this.type = type;
        this.nulls = new boolean[DEFAULT_BATCH_SIZE];
        switch (type) {
            case INT32 -> this.intValues = new int[DEFAULT_BATCH_SIZE];
            case INT64 -> this.longValues = new long[DEFAULT_BATCH_SIZE];
            case FLOAT64 -> this.doubleValues = new double[DEFAULT_BATCH_SIZE];
        }
    }

    public int getInt(int index) { return intValues[index]; }
    public long getLong(int index) { return longValues[index]; }
    public double getDouble(int index) { return doubleValues[index]; }
    public boolean isNull(int index) { return hasNulls && nulls[index]; }

    // Direct array access for tight loops (no bounds checking)
    public int[] getIntArray() { return intValues; }
    public long[] getLongArray() { return longValues; }
    public double[] getDoubleArray() { return doubleValues; }
    public boolean[] getNullArray() { return nulls; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public DataType getType() { return type; }

    public void reset() {
        this.size = 0;
        this.hasNulls = false;
        Arrays.fill(nulls, false);
    }
}
```

**Key insight:** Exposing raw arrays (not just `getInt(i)`) is critical. The JIT can only auto-vectorize tight loops over arrays — not method-call-per-element patterns.

---

## Step 2: VectorizedBatch — A Row Group in Memory

**File:** `execution/src/main/java/com/vksql/execution/vector/VectorizedBatch.java`

A batch holds multiple `ColumnVector`s — one per column — representing up to 1024 rows.

```java
public class VectorizedBatch {
    public static final int MAX_BATCH_SIZE = 1024;

    private final ColumnVector[] columns;
    private int size;                    // actual rows in this batch
    private SelectionVector selection;   // optional: which rows are "active"

    public VectorizedBatch(Schema schema) {
        this.columns = new ColumnVector[schema.columnCount()];
        for (int i = 0; i < schema.columnCount(); i++) {
            this.columns[i] = new ColumnVector(schema.get(i).type());
        }
    }

    public ColumnVector column(int index) { return columns[index]; }
    public int getSize() { return size; }
    public void setSize(int size) {
        this.size = size;
        for (ColumnVector col : columns) {
            col.setSize(size);
        }
    }

    public SelectionVector getSelection() { return selection; }
    public void setSelection(SelectionVector sel) { this.selection = sel; }
    public boolean hasSelection() { return selection != null; }

    public void reset() {
        this.size = 0;
        this.selection = null;
        for (ColumnVector col : columns) {
            col.reset();
        }
    }
}
```

---

## Step 3: SelectionVector — Avoid Materializing Filtered Rows

**File:** `execution/src/main/java/com/vksql/execution/vector/SelectionVector.java`

When a filter eliminates rows, you DON'T copy surviving rows into a new batch (expensive!). Instead, you record **which indices survived** in a selection vector.

```java
public class SelectionVector {
    private final int[] selectedIndices;
    private int selectedCount;

    public SelectionVector(int capacity) {
        this.selectedIndices = new int[capacity];
        this.selectedCount = 0;
    }

    public int[] getSelectedIndices() { return selectedIndices; }
    public int getSelectedCount() { return selectedCount; }
    public void setSelectedCount(int count) { this.selectedCount = count; }

    public int get(int position) { return selectedIndices[position]; }
    public void set(int position, int index) { selectedIndices[position] = index; }
}
```

**Example:**
- Batch has 1024 rows
- Filter `age > 30` matches rows at indices [2, 5, 7, 100, 512, ...]
- SelectionVector holds `[2, 5, 7, 100, 512, ...]`, count = 5
- Downstream operators iterate over only these indices

**Why not just compact the arrays?**
- Compacting means copying data for every column — expensive with wide tables
- Selection vector is a single `int[]` — cheap to produce, cheap to consume
- Multiple filters can be combined by intersecting selection vectors

---

## Step 4: Vectorized Expression Evaluation

**File:** `execution/src/main/java/com/vksql/execution/vector/expr/VectorExpression.java`

Expressions evaluate over entire vectors at once. The key pattern: a tight loop over the array with no virtual calls inside the loop body.

```java
public interface VectorExpression {
    /**
     * Evaluate this expression on the batch, writing results
     * into the output vector. Returns the output vector.
     */
    ColumnVector evaluate(VectorizedBatch batch);
}
```

**File:** `execution/src/main/java/com/vksql/execution/vector/expr/IntCompareExpr.java`

Example: `column_a > 100`

```java
public class IntCompareExpr implements VectorExpression {
    private final int columnIndex;
    private final int literal;
    private final ColumnVector output; // reusable output (boolean stored as int: 1/0)

    public IntCompareExpr(int columnIndex, int literal) {
        this.columnIndex = columnIndex;
        this.literal = literal;
        this.output = new ColumnVector(DataType.INT32); // 1 = true, 0 = false
    }

    @Override
    public ColumnVector evaluate(VectorizedBatch batch) {
        int[] input = batch.column(columnIndex).getIntArray();
        int[] result = output.getIntArray();
        int size = batch.getSize();

        // THIS is the hot loop — no virtual calls, no object allocation
        for (int i = 0; i < size; i++) {
            result[i] = input[i] > literal ? 1 : 0;
        }

        output.setSize(size);
        return output;
    }
}
```

**Why this is fast:** The JIT sees a simple loop over `int[]` with a compare + conditional store. It can:
1. Unroll the loop (process 4–8 iterations at once)
2. Use SIMD vector instructions (compare 8 ints simultaneously with AVX2)
3. Eliminate branch prediction misses (branchless conditional move)

---

## Step 5: Filter Operator Using Selection Vector

**File:** `execution/src/main/java/com/vksql/execution/vector/operator/VectorizedFilter.java`

The filter evaluates a predicate expression and produces a selection vector:

```java
public class VectorizedFilter {
    private final VectorExpression predicate;

    public VectorizedFilter(VectorExpression predicate) {
        this.predicate = predicate;
    }

    public void filter(VectorizedBatch batch) {
        ColumnVector predicateResult = predicate.evaluate(batch);
        int[] predicateValues = predicateResult.getIntArray();
        int batchSize = batch.getSize();

        SelectionVector sel = new SelectionVector(batchSize);
        int[] selected = sel.getSelectedIndices();
        int count = 0;

        // Tight loop: scan predicate results, collect passing indices
        for (int i = 0; i < batchSize; i++) {
            if (predicateValues[i] == 1) {
                selected[count++] = i;
            }
        }

        sel.setSelectedCount(count);
        batch.setSelection(sel);
    }
}
```

**Downstream operators** then respect the selection vector:

```java
// Aggregation with selection vector
public long sumWithSelection(VectorizedBatch batch, int columnIndex) {
    int[] values = batch.column(columnIndex).getIntArray();
    SelectionVector sel = batch.getSelection();
    int[] indices = sel.getSelectedIndices();
    int count = sel.getSelectedCount();
    long sum = 0;

    for (int i = 0; i < count; i++) {
        sum += values[indices[i]];
    }
    return sum;
}
```

---

## Step 6: Converting Volcano to Batch-at-a-Time

The Volcano model uses `next()` returning one row. The vectorized model uses `nextBatch()` returning a `VectorizedBatch`.

**Volcano interface (old):**
```java
public interface Operator {
    Row next();     // returns null when exhausted
    void open();
    void close();
}
```

**Vectorized interface (new):**
```java
public interface VectorizedOperator {
    /**
     * Fill the provided batch with the next chunk of data.
     * Returns false when there's no more data.
     */
    boolean next(VectorizedBatch batch);
    void open();
    void close();
}
```

**File:** `execution/src/main/java/com/vksql/execution/vector/operator/VectorizedScan.java`

Reads from your columnar storage, 1024 rows at a time:

```java
public class VectorizedScan implements VectorizedOperator {
    private final ColumnReader[] readers;
    private final Schema schema;
    private int currentRow = 0;
    private int totalRows;

    @Override
    public boolean next(VectorizedBatch batch) {
        if (currentRow >= totalRows) return false;

        int batchSize = Math.min(VectorizedBatch.MAX_BATCH_SIZE, totalRows - currentRow);
        batch.reset();

        for (int col = 0; col < schema.columnCount(); col++) {
            // Read directly into the batch's column vector arrays
            readers[col].readInto(batch.column(col), currentRow, batchSize);
        }

        batch.setSize(batchSize);
        currentRow += batchSize;
        return true;
    }
}
```

**Key difference from Volcano:** Each `next()` call does useful work on 1024 rows. The overhead of the virtual method call is amortized over 1024 rows instead of 1.

---

## Step 7: Vectorized Aggregation (SUM example)

**File:** `execution/src/main/java/com/vksql/execution/vector/operator/VectorizedSum.java`

```java
public class VectorizedSum implements VectorizedOperator {
    private final VectorizedOperator child;
    private final int columnIndex;
    private long runningSum = 0;
    private boolean exhausted = false;

    @Override
    public boolean next(VectorizedBatch outputBatch) {
        if (exhausted) return false;

        VectorizedBatch inputBatch = new VectorizedBatch(childSchema);

        while (child.next(inputBatch)) {
            int[] values = inputBatch.column(columnIndex).getIntArray();
            int size = inputBatch.getSize();

            if (inputBatch.hasSelection()) {
                SelectionVector sel = inputBatch.getSelection();
                int[] indices = sel.getSelectedIndices();
                int count = sel.getSelectedCount();
                for (int i = 0; i < count; i++) {
                    runningSum += values[indices[i]];
                }
            } else {
                // Tightest possible loop — JIT heaven
                for (int i = 0; i < size; i++) {
                    runningSum += values[i];
                }
            }

            inputBatch.reset();
        }

        // Emit a single-row result batch
        outputBatch.reset();
        outputBatch.column(0).getLongArray()[0] = runningSum;
        outputBatch.setSize(1);
        exhausted = true;
        return true;
    }
}
```

---

## Step 8: Why This Is Faster — The Performance Deep Dive

### 1. Virtual Dispatch Overhead (Volcano's Killer)

In Volcano, processing 10M rows means 10M virtual calls to `next()`. Each virtual call:
- Looks up the vtable
- May cause an indirect branch misprediction
- Prevents inlining (JIT can't inline megamorphic calls)

In vectorized: 10M rows / 1024 = ~9,766 virtual calls. That's a 1024x reduction.

### 2. CPU Cache Behavior

```
Volcano (row-at-a-time):
  Row 0: [col0_val, col1_val, col2_val, col3_val]  ← row object on heap (scattered)
  Row 1: [col0_val, col1_val, col2_val, col3_val]  ← different heap location

Vectorized (column-at-a-time):
  int[] col0 = [val0, val1, val2, ..., val1023]  ← contiguous in memory, fits in L1/L2
```

A modern L1 cache line is 64 bytes = 16 ints. Sequential access means the CPU prefetcher loads the next cache line before you need it. Volcano's scattered `Row` objects defeat the prefetcher.

### 3. JIT Auto-Vectorization (SIMD)

The JVM's C2 compiler can vectorize simple loops automatically:

```java
// This gets compiled to AVX2 instructions (8 ints at a time)
for (int i = 0; i < size; i++) {
    result[i] = input[i] > literal ? 1 : 0;
}
```

Requirements for auto-vectorization:
- Loop over primitive arrays
- No method calls inside the loop
- No complex control flow (simple conditionals OK)
- No aliasing (different arrays for input/output)

To verify SIMD is happening, run with:
```bash
java -XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly ...
```

Look for instructions like `vpcmpgtd`, `vpaddd`, `vmovdqu` — these are SIMD.

---

## Step 9: Benchmarking — Volcano vs. Vectorized

**File:** `execution/src/test/java/com/vksql/execution/benchmark/VolcanoVsVectorizedBenchmark.java`

Build a JMH benchmark comparing both approaches on a full table scan + filter:

**Query:** `SELECT SUM(amount) FROM sales WHERE region_id > 5`

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(2)
@State(Scope.Benchmark)
public class VolcanoVsVectorizedBenchmark {

    private static final int NUM_ROWS = 10_000_000;

    // Pre-generated data
    private int[] regionIds;
    private int[] amounts;

    @Setup
    public void setup() {
        var rng = new Random(42);
        regionIds = new int[NUM_ROWS];
        amounts = new int[NUM_ROWS];
        for (int i = 0; i < NUM_ROWS; i++) {
            regionIds[i] = rng.nextInt(10);
            amounts[i] = rng.nextInt(1000);
        }
    }

    @Benchmark
    public long volcanoStyle() {
        long sum = 0;
        for (int i = 0; i < NUM_ROWS; i++) {
            // Simulates: virtual next() call + Row object creation + field access
            if (regionIds[i] > 5) {
                sum += amounts[i];
            }
        }
        return sum;
    }

    @Benchmark
    public long vectorizedStyle() {
        long sum = 0;
        int[] sel = new int[VectorizedBatch.MAX_BATCH_SIZE];

        for (int offset = 0; offset < NUM_ROWS; offset += VectorizedBatch.MAX_BATCH_SIZE) {
            int batchSize = Math.min(VectorizedBatch.MAX_BATCH_SIZE, NUM_ROWS - offset);

            // Phase 1: Filter — produce selection vector
            int selCount = 0;
            for (int i = 0; i < batchSize; i++) {
                if (regionIds[offset + i] > 5) {
                    sel[selCount++] = i;
                }
            }

            // Phase 2: Aggregate — only touch selected rows
            for (int i = 0; i < selCount; i++) {
                sum += amounts[offset + sel[i]];
            }
        }
        return sum;
    }
}
```

**Add JMH to your build:**
```kotlin
// execution/build.gradle.kts
dependencies {
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
```

**Run:**
```bash
./gradlew :execution:jmh
# or manually:
java -jar execution/build/libs/benchmarks.jar VolcanoVsVectorized
```

**Expected results** (approximate, depends on hardware):
| Approach | 10M rows scan+filter+sum | Throughput |
|----------|-------------------------|------------|
| Volcano (row-at-a-time) | ~45–80 ms | ~130M rows/sec |
| Vectorized (batch) | ~8–15 ms | ~700M rows/sec |

The real Volcano model is even slower than the simplified benchmark above because it involves actual `Row` object allocation and vtable dispatch — the benchmark just shows the loop structure difference.

---

## Step 10: A More Realistic Volcano Baseline

To get a fair comparison, implement an actual Volcano-style scan + filter + sum:

```java
// Row-at-a-time Volcano
public interface VolcanoOperator {
    Row next();
}

public record Row(int[] values) {}

public class VolcanoScan implements VolcanoOperator {
    private int cursor = 0;
    private final int[][] columns;

    @Override
    public Row next() {
        if (cursor >= columns[0].length) return null;
        int[] row = new int[columns.length];
        for (int c = 0; c < columns.length; c++) {
            row[c] = columns[c][cursor];
        }
        cursor++;
        return new Row(row);
    }
}

public class VolcanoFilter implements VolcanoOperator {
    private final VolcanoOperator child;
    private final Predicate<Row> predicate;

    @Override
    public Row next() {
        Row row;
        while ((row = child.next()) != null) {
            if (predicate.test(row)) return row;
        }
        return null;
    }
}

public class VolcanoSum implements VolcanoOperator {
    // Consumes all from child, returns one Row with the sum
}
```

This Volcano implementation will be significantly slower than the vectorized one because of:
- `Row` object allocation per row (GC pressure)
- Virtual dispatch on `next()` per row
- Cache-unfriendly access patterns (rows scattered on heap)

---

## Order of Implementation

1. `ColumnVector` — raw array wrapper with null bitmap
2. `VectorizedBatch` — holds multiple ColumnVectors
3. `SelectionVector` — int[] of surviving indices
4. `VectorExpression` interface + `IntCompareExpr`
5. `VectorizedFilter` — produces SelectionVector from predicate
6. `VectorizedOperator` interface
7. `VectorizedScan` — reads from storage into batches
8. `VectorizedSum` — aggregation respecting selection vectors
9. Volcano baseline operators (for benchmarking)
10. JMH benchmark comparing both

---

## Common Mistakes

1. **Allocating inside the hot loop** — Never create objects inside your tight loop. Pre-allocate the output `ColumnVector` and reuse it across calls. One `new int[1024]` per expression evaluation call is fine; one `new` per row is not.

2. **Forgetting to handle SelectionVector in downstream operators** — Every operator after a filter must check `batch.hasSelection()` and iterate accordingly. If you ignore the selection vector, you process already-filtered-out rows.

3. **Using `Integer[]` instead of `int[]`** — Boxed arrays defeat the entire purpose. Auto-boxing in the hot path kills SIMD, generates garbage, and destroys cache locality. Always use primitive arrays.

4. **Making the batch size too large** — 1024 is the sweet spot. Too large (1M) and your working set doesn't fit in L1/L2 cache. Too small (32) and the per-batch overhead dominates. DuckDB uses 2048; MonetDB/X100 used 1024. Stick with 1024.

5. **Not resetting batches between iterations** — If batch #2 has 512 rows but you read 1024 from batch #1, the trailing 512 values from batch #1 are still in the array. Always check `batch.getSize()`, not the array length.

6. **Branchy code inside the tight loop** — Instead of `if (nulls[i]) continue;` inside the sum loop, separate null handling into a pre-pass or use the selection vector to exclude nulls. Branches inside hot loops kill pipelining.

7. **Benchmarking without warmup** — JMH handles this, but if you write manual benchmarks, always run 5+ warmup iterations so the JIT has compiled the hot methods. Cold code is interpreted and tells you nothing.

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Vectorized execution | Every operator processes arrays, not individual values |
| Selection vectors | Filter produces indices; downstream uses them |
| Data-oriented design | Arrays of primitives instead of arrays of objects |
| JIT auto-vectorization | Tight loops → SIMD instructions automatically |
| Cache-aware programming | L1 cache line = 64 bytes; batch fits in L2 |
| Amortized overhead | Virtual call cost shared across 1024 rows |

---

## When You're Done

- ✅ `ColumnVector` wraps `int[]`/`long[]`/`double[]` + null tracking
- ✅ `VectorizedBatch` holds a set of `ColumnVector`s for up to 1024 rows
- ✅ `SelectionVector` filters without data movement
- ✅ Expression evaluation runs in tight loops over raw arrays
- ✅ Vectorized scan reads from storage into batches
- ✅ JMH benchmark shows 5–10x speedup over Volcano on scan+filter+sum
- ✅ All tests pass, benchmark is reproducible

**Next week:** Hash Join and Hash Aggregation — building hash tables that work with vectorized batches, and partitioned/spilling strategies for large datasets.
