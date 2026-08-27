# Week 4: Predicate Pushdown + Benchmarks

## What You're Building

A query optimization layer that skips reading entire row groups when their min/max stats prove no rows can match a predicate. Plus projection pushdown (reading only requested columns) and JMH benchmarks to measure how much you're actually skipping.

---

## Why This Matters

Without predicate pushdown, reading `WHERE age > 50` scans every row group. With it, you check the footer stats first: if a row group's max age is 30, you skip the entire row group — zero I/O for millions of rows. This is how Parquet, ORC, and ClickHouse achieve sub-second scans over terabytes.

This technique is called **zone maps** — the min/max stats stored per column chunk act as a coarse index over data zones (row groups).

---

## Step 1: Understand Zone Map Filtering (on paper)

Given a file with 10 row groups and a query `WHERE price > 500`:

```
Row Group 0: price min=10,   max=200   → SKIP (max < 500, impossible to match)
Row Group 1: price min=50,   max=800   → READ (some values might match)
Row Group 2: price min=600,  max=950   → READ (all values might match)
Row Group 3: price min=1,    max=100   → SKIP
...
```

The decision logic is simple:
- If `max < threshold` → skip (for GT/GTE predicates)
- If `min > threshold` → skip (for LT/LTE predicates)
- If `min > value OR max < value` → skip (for EQ predicates)
- If `max < low OR min > high` → skip (for BETWEEN predicates)

You already have `min` and `max` stored in `ColumnChunkMetadata`. The infrastructure is ready.

---

## Step 2: Define the Predicate Interface

**File:** `storage/src/main/java/com/vksql/storage/predicate/Predicate.java`

A sealed interface with one key method: can this predicate possibly match a row group given its stats?

```java
public sealed interface Predicate
    permits GreaterThan, LessThan, EqualTo, Between {

    String columnName();
    boolean canSkipRowGroup(long min, long max);
}
```

The method `canSkipRowGroup` returns `true` if the row group is guaranteed to have **no matching rows** — meaning we can skip it entirely.

**Why sealed?** You control the set of implementations, enabling exhaustive `switch` expressions later without a default case.

---

## Step 3: Implement Predicate Variants

**Files (one per class, same package):**
- `GreaterThan.java` — matches when value > threshold
- `LessThan.java` — matches when value < threshold
- `EqualTo.java` — matches when value == target
- `Between.java` — matches when low <= value <= high

**Syntax hint — record implementing sealed interface:**
```java
public record GreaterThan(String columnName, long value) implements Predicate {
    @Override
    public boolean canSkipRowGroup(long min, long max) {
        // If the max value in this group is <= our threshold,
        // no value can be > threshold → skip
        return max <= value;
    }
}
```

**Think through each one:**

| Predicate | Skip when... | Reason |
|-----------|-------------|--------|
| `GT(x)` | `max <= x` | All values ≤ x, none > x |
| `LT(x)` | `min >= x` | All values ≥ x, none < x |
| `EQ(x)` | `x < min OR x > max` | x is outside the range |
| `BETWEEN(lo, hi)` | `max < lo OR min > hi` | Range doesn't overlap |

**Common mistake:** Getting the skip condition backwards. Remember: `canSkipRowGroup` returns `true` when you can SKIP (no matches possible), not when matches exist.

---

## Step 4: Build a RowGroupFilter

**File:** `storage/src/main/java/com/vksql/storage/predicate/RowGroupFilter.java`

A class that takes a list of predicates and a `FileFooter`, and returns which row group indices to read.

```java
public class RowGroupFilter {
    private final List<Predicate> predicates;

    public List<Integer> filterRowGroups(FileFooter footer) {
        // For each row group, check all predicates
        // If ANY predicate says "skip", skip the group
        // Return indices of groups that survive
    }
}
```

**Logic:**
1. Iterate over `footer.rows()` (list of `RowGroupMetadata`)
2. For each row group, iterate over predicates
3. For each predicate, find the matching `ColumnChunkMetadata` by column name
4. Call `predicate.canSkipRowGroup(chunk.min(), chunk.max())`
5. If any predicate says skip → exclude this row group
6. Collect surviving row group indices

**Syntax hint — finding column metadata:**
```java
RowGroupMetadata rg = footer.rows().get(i);
ColumnChunkMetadata chunk = rg.columns().stream()
    .filter(c -> c.name().equals(predicate.columnName()))
    .findFirst()
    .orElseThrow();
```

---

## Step 5: Modify the Reader for Predicate Pushdown

**File:** Modify `VksqlFileReader.java` or create a new `ScanExecutor.java`

Add a scan method that accepts predicates and only reads surviving row groups:

```java
public List<ColumnData> scan(List<String> columns, List<Predicate> predicates) {
    // 1. Filter row groups using predicates + footer stats
    // 2. For surviving groups, read only the requested columns
    // 3. Return column data
}
```

The reader already knows how to seek to a column chunk's offset (from `ColumnChunkMetadata.fileOffSet()`). Now you just skip the ones that don't pass the filter.

**Key insight:** You're not filtering individual rows here — you're filtering entire row groups (potentially millions of rows each). Fine-grained row filtering happens later during execution.

---

## Step 6: Projection Pushdown (Read Only Needed Columns)

Currently your reader reads all columns in a row group. For `SELECT price, name FROM orders`, you should only read the `price` and `name` column chunks, skipping the rest.

**Modify the scan to accept a column list:**
1. Accept `List<String> projectedColumns` — the columns the query needs
2. For each surviving row group, only read `ColumnChunkMetadata` entries whose name is in the projection list
3. Skip seeking/reading for other columns entirely

**Syntax hint — filtering columns:**
```java
Set<String> needed = new HashSet<>(projectedColumns);
List<ColumnChunkMetadata> toRead = rowGroupMeta.columns().stream()
    .filter(c -> needed.contains(c.name()))
    .toList();
```

**Why this matters for performance:** A table with 50 columns where you only need 2 means you skip 96% of the I/O. Combined with row group skipping, you might read <1% of the file.

---

## Step 7: Write Tests for Predicate Pushdown

```java
@Test
void skipRowGroupsWithGreaterThan() {
    // Write file with known data ranges per row group
    // Apply GT predicate that should skip some groups
    // Verify fewer groups are read
}

@Test
void skipRowGroupsWithBetween() {
    // Write data where only 2 of 10 row groups overlap the range
    // Verify 8 groups skipped
}

@Test
void projectionReadsOnlyRequestedColumns() {
    // Write 5-column table
    // Read only 2 columns
    // Verify only 2 column chunks were accessed
}

@Test
void combinedPredicateAndProjection() {
    // Both row group skipping AND column pruning active
    // Verify minimal I/O
}
```

**Test strategy:** Write data with carefully controlled ranges. For example, write row groups where group 0 has values [0, 999], group 1 has [1000, 1999], etc. Then predicates produce predictable skip/read decisions.

---

## Step 8: Benchmarking — Measure What You Skip

**File:** `storage/src/main/java/com/vksql/storage/bench/ScanBenchmark.java` (or in a separate `benchmark` module)

Before JMH, start with a simple manual benchmark to validate your optimization works:

```java
public class ScanBenchmark {
    public static void main(String[] args) {
        // 1. Write a large file (10M+ rows, multiple row groups)
        // 2. Scan WITH predicates — count groups read, measure time
        // 3. Scan WITHOUT predicates — count groups read, measure time
        // 4. Print: groups skipped, throughput (bytes/sec), speedup factor
    }
}
```

**Metrics to capture:**
- Row groups total vs. row groups actually read
- Bytes read (sum of column chunk sizes for read groups)
- Wall-clock time for the scan
- Throughput: `bytesRead / timeInSeconds` → report as MB/s or GB/s
- Speedup: `timeWithout / timeWith`

**Syntax hint — measuring time:**
```java
long start = System.nanoTime();
// ... do work ...
long elapsed = System.nanoTime() - start;
double seconds = elapsed / 1_000_000_000.0;
double gbPerSec = bytesRead / (1024.0 * 1024 * 1024) / seconds;
```

---

## Step 9: JMH Setup for Micro-Benchmarks

JMH (Java Microbenchmark Harness) is the standard way to benchmark Java code without being fooled by JIT, GC, or warmup effects.

**Add JMH dependency to `storage/build.gradle.kts` (or a new `benchmark` module):**
```kotlin
plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
```

**Alternatively, create a dedicated `benchmark` submodule** to keep benchmark code separate from production code.

**Syntax hint — JMH benchmark class:**
```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class PredicatePushdownBenchmark {

    private Path dataFile;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // Write a test file with known data
        // This runs once before all iterations
        dataFile = Files.createTempFile("bench", ".vkql");
        // ... write 10M rows ...
    }

    @Benchmark
    public void scanWithPredicate(Blackhole bh) throws Exception {
        // Scan with predicate pushdown
        var results = reader.scan(columns, predicates);
        bh.consume(results); // prevent dead code elimination
    }

    @Benchmark
    public void scanFullTable(Blackhole bh) throws Exception {
        // Scan without predicates (full scan)
        var results = reader.scan(columns, List.of());
        bh.consume(results);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        Files.deleteIfExists(dataFile);
    }
}
```

**Running JMH:**
```bash
./gradlew :storage:jmh
# or if separate module:
./gradlew :benchmark:jmh
```

**Key JMH concepts:**
- `@Warmup` — lets JIT optimize before measuring
- `@Fork` — runs in a separate JVM to avoid interference
- `Blackhole` — consumes results so the JVM doesn't optimize away your benchmark
- `@State` — holds benchmark state (files, readers, etc.)
- `Mode.Throughput` — measures ops/sec; `Mode.AverageTime` — measures avg time per op

---

## Step 10: Add Benchmarks for Projection Pushdown

```java
@Benchmark
public void scanAllColumns(Blackhole bh) throws Exception {
    var results = reader.scan(allColumns, List.of());
    bh.consume(results);
}

@Benchmark
public void scanTwoColumns(Blackhole bh) throws Exception {
    var results = reader.scan(List.of("price", "quantity"), List.of());
    bh.consume(results);
}
```

This demonstrates how reading 2 out of 10 columns gives ~5x throughput improvement.

**Additional benchmark ideas:**
- Predicate selectivity: skip 90% vs 50% vs 10% of row groups
- Different data types: INT32 vs INT64 vs STRING filtering
- Combined: projection + predicate pushdown together
- Page decoding: measure raw decode speed (bytes → values)

---

## Order of Implementation

1. `Predicate` sealed interface
2. `GreaterThan`, `LessThan`, `EqualTo`, `Between` records
3. `RowGroupFilter` — evaluates predicates against footer stats
4. Modify `VksqlFileReader` (or new `ScanExecutor`) — skip filtered row groups
5. Add projection pushdown — read only requested columns
6. Unit tests with controlled data ranges
7. Manual benchmark (`main()` method) — validate speedup
8. JMH setup (gradle plugin + dependencies)
9. JMH benchmark class — formalize measurements
10. Run benchmarks, collect results

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Zone maps | Min/max stats enabling row group skipping |
| Predicate pushdown | Pushing WHERE filters below the scan operator |
| Projection pushdown | Reading only needed columns |
| Sealed interfaces | Closed type hierarchies for exhaustive matching |
| JMH | Rigorous Java microbenchmarking without JIT/GC noise |
| I/O amplification | Measuring wasted reads vs. useful reads |
| Throughput metrics | GB/s as the key storage engine metric |

---

## Common Mistakes

1. **Inverted skip logic** — `canSkipRowGroup` returns true when you CAN skip (guaranteed no matches). Don't confuse it with "can match." Test with obvious cases: GT(1000) on a group with max=500 → must skip.
2. **Forgetting GTE/LTE edge cases** — `GT(5)` on a group with max=5: no value can be greater than 5 in this group (max IS 5), so skip. But `GTE(5)` on max=5: the value 5 exists, don't skip. Start with strict GT/LT and add GTE/LTE later.
3. **Stats are longs** — your `ColumnChunkMetadata` stores min/max as `long`. FLOAT64 values were cast to long for stats (from Week 1). Predicate filtering on doubles will be lossy. Accept this for now; fix later with proper typed stats.
4. **Reading all columns then filtering** — projection pushdown means you DON'T read unneeded columns at all. Don't read everything and then discard — that defeats the purpose.
5. **JMH: not using Blackhole** — if you don't consume benchmark results, JIT may eliminate your entire computation as dead code. Always `bh.consume(result)`.
6. **JMH: benchmarking setup cost** — file creation goes in `@Setup`, not inside `@Benchmark`. You're measuring scan speed, not file creation.
7. **Measuring wall clock in JMH** — don't use `System.nanoTime()` inside JMH benchmarks. JMH handles timing. Manual timing is only for your simple benchmark script.

---

## Expected Results

For a 10-row-group file with sequential data ranges:
- A selective predicate (matches 1 group) should show ~10x speedup over full scan
- Projection of 2/10 columns should show ~5x throughput improvement
- Combined: up to 50x less I/O than a naive full scan

Throughput targets (rough, depends on hardware):
- Cold read (SSD): 500MB/s - 2GB/s
- Hot read (OS page cache): 2-8 GB/s
- With predicate skipping: effective throughput appears much higher (bytes "processed" / time)

---

## When You're Done

- ✅ Predicate interface with GT, LT, EQ, BETWEEN implementations
- ✅ RowGroupFilter correctly identifies row groups to skip using footer stats
- ✅ Reader skips filtered row groups (zero I/O for skipped groups)
- ✅ Projection pushdown reads only requested columns
- ✅ Tests verify correct skip behavior with controlled data
- ✅ Manual benchmark shows measurable speedup with predicates
- ✅ JMH benchmarks run via Gradle and produce throughput numbers
- ✅ You can report: "Scanned 10M rows, skipped 8/10 row groups, read 40MB instead of 200MB in 0.02s"

**Next week:** Query execution — building a vectorized batch engine that processes column vectors instead of row-at-a-time.
