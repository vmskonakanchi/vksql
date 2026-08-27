# Week 7: Physical Plan + Volcano Execution

## What You're Building

A **pull-based query execution engine** using the Volcano/Iterator model. Each operator is a node in a tree that produces one batch of rows at a time. By the end of this week, you can run `SELECT * FROM orders WHERE price > 100` end-to-end — from columnar file to filtered result batches.

## Why Volcano (Pull-Based)?

There are two ways to wire operators together:

**Push-based** — data flows downward. A producer pushes rows into consumers:
```
TableScan → Filter → Project → Output
  "here's 1024 rows"  →  →  →
```

**Pull-based (Volcano)** — data flows upward on demand. A consumer *pulls* from its child:
```
Output calls next() on Project
  Project calls next() on Filter
    Filter calls next() on TableScan
      TableScan reads a batch from disk, returns it
    Filter evaluates predicate, returns matching rows
  Project selects columns, returns trimmed batch
Output prints/stores results
```

**Why pull?** It's simpler to implement, easy to reason about, and naturally supports `LIMIT` (just stop calling `next()`). Every real database started with Volcano before evolving to push/vectorized hybrids.

**Vectorized twist:** Classic Volcano returns one row at a time. We return a **batch** (1024 rows) at a time — same pull model, but amortizes function call overhead across many rows. This is what DuckDB and modern engines do.

---

## Step 0: Module Setup

Create an `execution` module alongside `storage`:

**settings.gradle.kts** — add `include("execution")`

**execution/build.gradle.kts:**
```kotlin
plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":storage"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

**Package:** `com.vksql.execution`

---

## Step 1: Define RecordBatch

A `RecordBatch` is the unit of data that flows between operators. It holds N rows stored as **column vectors** — arrays of values, one per column.

**File:** `execution/src/main/java/com/vksql/execution/RecordBatch.java`

```java
public class RecordBatch {
    private final Schema schema;
    private final ColumnVector[] columns;
    private final int rowCount;

    public RecordBatch(Schema schema, ColumnVector[] columns, int rowCount) { ... }

    public Schema schema() { return schema; }
    public int rowCount() { return rowCount; }
    public ColumnVector column(int index) { return columns[index]; }
    public int columnCount() { return columns.length; }
}
```

**Think about:** A `RecordBatch` with `rowCount == 0` is valid (filter removed all rows). A `null` return from `next()` means "no more data".

---

## Step 2: Define ColumnVector

A `ColumnVector` wraps a typed array. It's how you access values within a batch without boxing every value into an Object.

**File:** `execution/src/main/java/com/vksql/execution/ColumnVector.java`

```java
public interface ColumnVector {
    DataType dataType();
    int size();
    boolean isNull(int index);

    int getInt(int index);
    long getLong(int index);
    double getDouble(int index);
    String getString(int index);
}
```

Then create concrete implementations:

**File:** `execution/src/main/java/com/vksql/execution/IntColumnVector.java`
```java
public class IntColumnVector implements ColumnVector {
    private final int[] values;
    private final boolean[] nulls; // optional, can be null if no nulls

    public IntColumnVector(int[] values, boolean[] nulls) { ... }

    @Override public DataType dataType() { return DataType.INT32; }
    @Override public int size() { return values.length; }
    @Override public boolean isNull(int index) { return nulls != null && nulls[index]; }
    @Override public int getInt(int index) { return values[index]; }
    @Override public long getLong(int index) { throw new UnsupportedOperationException(); }
    // ... etc
}
```

Similarly: `LongColumnVector`, `DoubleColumnVector`, `StringColumnVector`.

**Syntax hint — typed arrays are fast:**
```java
int[] values = new int[1024];
// No boxing, no Object overhead, cache-friendly sequential access
for (int i = 0; i < values.length; i++) {
    sum += values[i]; // this is FAST — CPU prefetcher loves this
}
```

---

## Step 3: Define PhysicalOperator Interface

This is the Volcano contract. Every operator implements these three methods:

**File:** `execution/src/main/java/com/vksql/execution/PhysicalOperator.java`

```java
public interface PhysicalOperator {
    /**
     * Initialize the operator. Open files, allocate buffers.
     * Must be called before next().
     */
    void open();

    /**
     * Pull the next batch of rows.
     * Returns null when there is no more data.
     */
    RecordBatch next();

    /**
     * Release resources. Close files, free buffers.
     */
    void close();

    /**
     * The schema of batches this operator produces.
     */
    Schema outputSchema();
}
```

**The lifecycle:**
```
open() → next() → next() → ... → next() returns null → close()
```

**Rules:**
- `next()` must not be called before `open()`
- `next()` must not be called after it returns `null`
- `close()` must always be called (use try-finally or try-with-resources)

---

## Step 4: Implement TableScanOp

The leaf operator — reads batches from your columnar storage files.

**File:** `execution/src/main/java/com/vksql/execution/operator/TableScanOp.java`

```java
public class TableScanOp implements PhysicalOperator {
    private final Path filePath;
    private final Schema schema;
    private final int batchSize; // e.g., 1024

    private VksqlFileReader reader;
    private int currentRowGroup;
    private int currentOffsetInRowGroup;
    private FileFooter footer;

    public TableScanOp(Path filePath, Schema schema, int batchSize) { ... }

    @Override
    public void open() {
        this.reader = new VksqlFileReader(filePath);
        this.footer = reader.getFooter();
        this.currentRowGroup = 0;
        this.currentOffsetInRowGroup = 0;
    }

    @Override
    public RecordBatch next() {
        // 1. If all row groups exhausted → return null
        // 2. Read next batchSize rows from current row group
        // 3. Build ColumnVector[] from the raw data
        // 4. Advance offset. If row group exhausted, move to next
        // 5. Return new RecordBatch(schema, columns, actualRowCount)
    }

    @Override
    public void close() {
        reader.close();
    }

    @Override
    public Schema outputSchema() { return schema; }
}
```

**Key design decision:** How much data does `next()` read?

Option A: Read one full row group at a time (could be 1M rows — too big, defeats vectorization purpose).

Option B: Read `batchSize` rows (e.g., 1024) from the current position within a row group.

**Use Option B.** The batch size should fit in L1/L2 cache. 1024 rows × 8 bytes = 8KB per column — fits easily.

**Syntax hint — reading a slice of column data:**
```java
// You'll need to extend your ColumnReader to support offset/limit reads
int[] slice = columnReader.readInts(offset, batchSize);
```

---

## Step 5: Implement FilterOp

Takes a child operator and a predicate. Pulls batches from the child, evaluates the predicate on each row, and returns only matching rows.

**File:** `execution/src/main/java/com/vksql/execution/operator/FilterOp.java`

```java
public class FilterOp implements PhysicalOperator {
    private final PhysicalOperator child;
    private final Predicate predicate;

    public FilterOp(PhysicalOperator child, Predicate predicate) { ... }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public RecordBatch next() {
        while (true) {
            RecordBatch batch = child.next();
            if (batch == null) return null;

            // Evaluate predicate → get selection vector (which rows pass)
            boolean[] selected = predicate.evaluate(batch);

            // Count how many passed
            int selectedCount = countTrue(selected);
            if (selectedCount == 0) continue; // skip empty batches

            // Compact: build new ColumnVectors with only selected rows
            ColumnVector[] filtered = compactBatch(batch, selected, selectedCount);
            return new RecordBatch(batch.schema(), filtered, selectedCount);
        }
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public Schema outputSchema() { return child.outputSchema(); }
}
```

**Important:** The `while (true)` loop. If a batch has zero matches, don't return an empty batch — pull the next one. Only return `null` when the child is exhausted.

**Now define Predicate:**

**File:** `execution/src/main/java/com/vksql/execution/expr/Predicate.java`

```java
public interface Predicate {
    /**
     * Evaluate predicate on each row in the batch.
     * Returns boolean[] where true = row passes.
     */
    boolean[] evaluate(RecordBatch batch);
}
```

**File:** `execution/src/main/java/com/vksql/execution/expr/ComparisonPredicate.java`

```java
public class ComparisonPredicate implements Predicate {
    private final int columnIndex;
    private final CompOp op; // GT, LT, EQ, GTE, LTE, NEQ
    private final long literalValue; // for numeric comparisons

    public ComparisonPredicate(int columnIndex, CompOp op, long literalValue) { ... }

    @Override
    public boolean[] evaluate(RecordBatch batch) {
        boolean[] result = new boolean[batch.rowCount()];
        ColumnVector col = batch.column(columnIndex);

        for (int i = 0; i < batch.rowCount(); i++) {
            if (col.isNull(i)) {
                result[i] = false; // NULLs never pass comparisons
                continue;
            }
            long value = switch (col.dataType()) {
                case INT32 -> col.getInt(i);
                case INT64 -> col.getLong(i);
                case FLOAT64 -> Double.doubleToRawLongBits(col.getDouble(i));
                default -> throw new UnsupportedOperationException();
            };
            result[i] = compare(value, op, literalValue);
        }
        return result;
    }

    private boolean compare(long left, CompOp op, long right) {
        return switch (op) {
            case GT -> left > right;
            case LT -> left < right;
            case EQ -> left == right;
            case GTE -> left >= right;
            case LTE -> left <= right;
            case NEQ -> left != right;
        };
    }
}
```

**Syntax hint — compacting a column vector (selecting matching rows):**
```java
private int[] compact(int[] source, boolean[] selected, int count) {
    int[] result = new int[count];
    int j = 0;
    for (int i = 0; i < source.length; i++) {
        if (selected[i]) {
            result[j++] = source[i];
        }
    }
    return result;
}
```

---

## Step 6: Implement ProjectOp

Takes a child operator and a list of column indices to keep. Strips out unneeded columns.

**File:** `execution/src/main/java/com/vksql/execution/operator/ProjectOp.java`

```java
public class ProjectOp implements PhysicalOperator {
    private final PhysicalOperator child;
    private final int[] columnIndices; // which columns to keep
    private final Schema projectedSchema;

    public ProjectOp(PhysicalOperator child, int[] columnIndices) {
        this.child = child;
        this.columnIndices = columnIndices;
        // Build projected schema from child's schema
        this.projectedSchema = buildProjectedSchema(child.outputSchema(), columnIndices);
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public RecordBatch next() {
        RecordBatch batch = child.next();
        if (batch == null) return null;

        ColumnVector[] projected = new ColumnVector[columnIndices.length];
        for (int i = 0; i < columnIndices.length; i++) {
            projected[i] = batch.column(columnIndices[i]);
        }
        return new RecordBatch(projectedSchema, projected, batch.rowCount());
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public Schema outputSchema() { return projectedSchema; }
}
```

**Note:** ProjectOp is cheap — it just picks columns from the batch. No data copying needed (just reference the same ColumnVector). This is called **zero-copy projection**.

---

## Step 7: Implement LimitOp

Stops pulling after N rows have been emitted. This is where pull-based shines — you just stop calling `next()`.

**File:** `execution/src/main/java/com/vksql/execution/operator/LimitOp.java`

```java
public class LimitOp implements PhysicalOperator {
    private final PhysicalOperator child;
    private final int limit;
    private int emitted;

    public LimitOp(PhysicalOperator child, int limit) { ... }

    @Override
    public void open() {
        child.open();
        this.emitted = 0;
    }

    @Override
    public RecordBatch next() {
        if (emitted >= limit) return null;

        RecordBatch batch = child.next();
        if (batch == null) return null;

        int remaining = limit - emitted;
        if (batch.rowCount() <= remaining) {
            emitted += batch.rowCount();
            return batch;
        } else {
            // Trim batch to only 'remaining' rows
            ColumnVector[] trimmed = trimBatch(batch, remaining);
            emitted += remaining;
            return new RecordBatch(batch.schema(), trimmed, remaining);
        }
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public Schema outputSchema() { return child.outputSchema(); }
}
```

**Syntax hint — trimming a column vector:**
```java
private int[] trim(int[] source, int newLength) {
    return Arrays.copyOf(source, newLength);
}
```

**Why pull-based makes LIMIT trivial:** In push-based, you'd need a way to signal upstream to stop producing. In pull-based, you just… stop calling `next()`. The child never reads more data than needed.

---

## Step 8: Mapping Logical Plan to Physical Operators

This is where the planner output from earlier weeks connects to execution. The mapping is straightforward for basic queries:

| Logical Node | Physical Operator |
|-------------|-------------------|
| `Scan("orders")` | `TableScanOp(path, schema, 1024)` |
| `Filter(predicate)` | `FilterOp(child, predicate)` |
| `Project(columns)` | `ProjectOp(child, columnIndices)` |
| `Limit(n)` | `LimitOp(child, n)` |

**File:** `execution/src/main/java/com/vksql/execution/PhysicalPlanner.java`

```java
public class PhysicalPlanner {
    private final Map<String, Path> tableRegistry; // table name → file path
    private final Map<String, Schema> schemaRegistry;

    public PhysicalOperator plan(LogicalPlan logicalPlan) {
        return switch (logicalPlan) {
            case ScanNode scan -> new TableScanOp(
                tableRegistry.get(scan.tableName()),
                schemaRegistry.get(scan.tableName()),
                1024
            );
            case FilterNode filter -> new FilterOp(
                plan(filter.child()),
                translatePredicate(filter.predicate())
            );
            case ProjectNode project -> new ProjectOp(
                plan(project.child()),
                resolveColumnIndices(project.columns(), project.child().outputSchema())
            );
            case LimitNode limit -> new LimitOp(
                plan(limit.child()),
                limit.count()
            );
            default -> throw new UnsupportedOperationException("Unknown node: " + logicalPlan);
        };
    }
}
```

For now, if you haven't built the planner yet, you can construct operators directly in tests (see Step 9).

---

## Step 9: End-to-End — `SELECT * FROM orders WHERE price > 100`

Put it all together. Even without a parser/planner, you can wire operators manually:

```java
@Test
void endToEndFilterQuery() throws Exception {
    // 1. Create test data file
    Path testFile = createOrdersFile(); // writes orders with price column

    // 2. Define schema
    Schema schema = new Schema(List.of(
        new ColumnDescriptor("order_id", DataType.INT32, 0),
        new ColumnDescriptor("customer_id", DataType.INT32, 1),
        new ColumnDescriptor("price", DataType.FLOAT64, 2)
    ));

    // 3. Build operator tree (bottom-up)
    PhysicalOperator scan = new TableScanOp(testFile, schema, 1024);
    PhysicalOperator filter = new FilterOp(scan,
        new ComparisonPredicate(2, CompOp.GT, Double.doubleToRawLongBits(100.0))
    );
    // SELECT * means no projection needed — keep all columns

    // 4. Execute
    filter.open();
    try {
        List<RecordBatch> results = new ArrayList<>();
        RecordBatch batch;
        while ((batch = filter.next()) != null) {
            results.add(batch);
        }

        // 5. Verify
        int totalRows = results.stream().mapToInt(RecordBatch::rowCount).sum();
        System.out.println("Rows with price > 100: " + totalRows);

        // Check all prices are > 100
        for (RecordBatch b : results) {
            ColumnVector priceCol = b.column(2);
            for (int i = 0; i < b.rowCount(); i++) {
                assertTrue(priceCol.getDouble(i) > 100.0);
            }
        }
    } finally {
        filter.close();
    }
}
```

**Helper to create test data:**
```java
private Path createOrdersFile() throws IOException {
    Path path = Path.of("test_orders.vksql");
    Schema schema = new Schema(List.of(
        new ColumnDescriptor("order_id", DataType.INT32, 0),
        new ColumnDescriptor("customer_id", DataType.INT32, 1),
        new ColumnDescriptor("price", DataType.FLOAT64, 2)
    ));

    try (var writer = new VksqlFileWriter(path, schema)) {
        var random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            writer.writeRow(i, random.nextInt(1000), random.nextDouble() * 200.0);
        }
    }
    return path;
}
```

---

## The Pull Model in Action (Trace)

Here's exactly what happens when you call `filter.next()` the first time:

```
1. filter.next() called
2.   filter calls child.next() → scan.next()
3.     scan reads rows 0-1023 from row group 0
4.     scan builds ColumnVector[3] → returns RecordBatch(1024 rows)
5.   filter evaluates: price > 100 for all 1024 rows
6.     suppose 512 rows pass
7.   filter compacts batch to 512 rows → returns RecordBatch(512 rows)
8. caller receives 512 rows
```

On second call to `filter.next()`:
```
1. filter.next() called
2.   filter calls child.next() → scan.next()
3.     scan reads rows 1024-2047 → returns RecordBatch(1024 rows)
4.   filter evaluates predicate, say 480 pass
5.   filter returns RecordBatch(480 rows)
```

If filter gets a batch where 0 rows pass:
```
1. filter.next() called
2.   filter calls child.next() → scan.next()
3.     scan returns RecordBatch(1024 rows)
4.   filter evaluates — 0 rows pass
5.   filter does NOT return empty batch — loops back to step 2
6.   filter calls child.next() again → scan returns next batch
7.   ... eventually returns a non-empty batch or null
```

---

## Order of Implementation

1. `ColumnVector` interface + concrete implementations (IntColumnVector, LongColumnVector, DoubleColumnVector, StringColumnVector)
2. `RecordBatch` class
3. `PhysicalOperator` interface
4. `TableScanOp` — get this working first, test it standalone
5. `Predicate` interface + `ComparisonPredicate`
6. `FilterOp` — test with hardcoded batches (no file I/O)
7. `ProjectOp` — test with hardcoded batches
8. `LimitOp` — test with hardcoded batches
9. Integration test: TableScan → Filter → full end-to-end
10. Integration test: TableScan → Filter → Project → Limit

**Test each operator in isolation first**, then compose them.

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Volcano/Iterator model | PhysicalOperator — open/next/close lifecycle |
| Vectorized execution | RecordBatch — processing 1024 rows at a time, not 1 |
| Zero-copy projection | ProjectOp — reusing column vector references |
| Selection vectors | FilterOp — boolean[] marking which rows pass |
| Pull-based control flow | LimitOp — just stop calling next() |
| Operator composition | Chaining operators into a tree |

---

## Common Mistakes

1. **Returning empty batches from FilterOp.** If a predicate filters out all rows in a batch, don't return a batch with 0 rows. Loop and pull the next batch from the child. Only return `null` when the child is exhausted.

2. **Forgetting to call open() on children.** Each operator's `open()` must call `child.open()`. If you forget, the child tries to produce data without initializing its resources.

3. **Not calling close() on error paths.** Use try-finally:
   ```java
   operator.open();
   try {
       // process batches
   } finally {
       operator.close();
   }
   ```

4. **Reading the entire file in TableScanOp.open().** Don't. The point of the pull model is lazy evaluation. Read one batch at a time in `next()`. Only read the footer in `open()`.

5. **Float comparison with bits.** If you cast doubles to `long` via `doubleToRawLongBits`, the comparison semantics change (bit patterns don't sort the same as doubles for negative values). Better to keep a separate `double` literal field in the predicate and compare doubles directly:
   ```java
   // WRONG for negative numbers:
   long bits = Double.doubleToRawLongBits(col.getDouble(i));
   return bits > literalBits;

   // CORRECT:
   double value = col.getDouble(i);
   return value > literalDouble;
   ```

6. **Variable batch sizes.** FilterOp returns batches with fewer rows than the input batch (because some rows were removed). Downstream operators must handle variable-length batches — don't assume every batch has exactly `batchSize` rows.

7. **Not handling the last batch.** The final batch from TableScanOp may have fewer rows than `batchSize` (e.g., 10000 rows / 1024 = 9 full batches + 1 batch of 784 rows). Always use `batch.rowCount()`, never assume 1024.

8. **Modifying column vectors in place.** ColumnVectors should be treated as immutable. ProjectOp returns references to the *same* vectors. If FilterOp modifies them in place, it corrupts data for other operators sharing the reference. Always create new vectors when filtering.

---

## Testing Strategy

**Unit tests (per operator, no file I/O):**
```java
@Test
void filterRemovesRows() {
    // Create a RecordBatch manually
    int[] ids = {1, 2, 3, 4, 5};
    double[] prices = {50.0, 150.0, 75.0, 200.0, 99.0};
    RecordBatch batch = createBatch(ids, prices);

    // Wrap in a mock operator that returns this one batch then null
    PhysicalOperator source = new SingleBatchOp(batch);
    PhysicalOperator filter = new FilterOp(source,
        new ComparisonPredicate(1, CompOp.GT, 100.0));

    filter.open();
    RecordBatch result = filter.next();
    assertEquals(2, result.rowCount()); // only 150.0 and 200.0 pass
    assertNull(filter.next());
    filter.close();
}
```

**Helper for testing — a fixed-data operator:**
```java
public class SingleBatchOp implements PhysicalOperator {
    private final List<RecordBatch> batches;
    private int index;

    public SingleBatchOp(RecordBatch... batches) {
        this.batches = List.of(batches);
    }

    @Override public void open() { index = 0; }
    @Override public RecordBatch next() {
        return index < batches.size() ? batches.get(index++) : null;
    }
    @Override public void close() {}
    @Override public Schema outputSchema() { return batches.get(0).schema(); }
}
```

---

## Performance Notes

- **Batch size of 1024** is a good default. It fits in L2 cache for most column widths. Smaller = more function call overhead. Larger = more memory pressure and less cache-friendly.
- **FilterOp compaction** is the most expensive part. You're copying data. A future optimization: use a **selection vector** (int[] of passing indices) instead of physically copying rows. But start with compaction — it's simpler.
- **TableScanOp** should ideally skip row groups entirely if their min/max stats show the predicate can't match. That's predicate pushdown — tackle it after the basics work.

---

## When You're Done

- ✅ `PhysicalOperator` interface with open/next/close lifecycle
- ✅ `RecordBatch` with typed `ColumnVector` arrays
- ✅ `TableScanOp` reads batches from your columnar files
- ✅ `FilterOp` evaluates predicates and removes non-matching rows
- ✅ `ProjectOp` selects/reorders columns (zero-copy)
- ✅ `LimitOp` stops execution after N rows
- ✅ End-to-end: write data → scan → filter → verify results
- ✅ All operators tested in isolation AND composed together

**Next week:** HashJoin operator + aggregation (GROUP BY, SUM, COUNT).
