# Week 9: Hash Join + Hash Aggregate + Sort

## What You're Building

The three heavyweight operators that make analytical queries fast: **Hash Join** (equi-joins without sorting), **Hash Aggregate** (GROUP BY without pre-sorting), and **Sort** (ORDER BY, plus sort-merge join as an alternative join strategy). By the end, you can run TPC-H Q1 and Q6 end-to-end on ~1GB of data.

---

## Why This Matters

Up to now your execution engine can scan, filter, and project. But analytics requires combining tables (JOIN) and summarizing (GROUP BY). Without these operators, you can't answer "revenue per nation" or "top 10 customers by spend."

Hash-based operators dominate analytical engines because they're **O(n)** instead of **O(n log n)** — no sorting required. But you also need sort for ORDER BY and as a fallback join strategy when hash tables won't fit in memory.

---

## Step 1: Design the Hash Table (Open Addressing, Linear Probing)

Before writing any operator, build the hash table that both Hash Join and Hash Aggregate will use. Do NOT use `java.util.HashMap` — it's designed for general use, not vectorized execution.

**Why open addressing over chaining?**
- Chaining: each bucket is a linked list → pointer chasing → cache misses
- Open addressing: all entries live in one flat array → sequential memory access → cache-friendly

**Why linear probing over quadratic/robin hood?**
- Simplest to implement
- With a good load factor (≤ 0.7), performance is excellent
- Probe sequences hit consecutive cache lines

**File:** `execution/src/main/java/com/vksql/execution/hash/RawHashTable.java`

**Layout — a flat array of "slots":**
```
┌────────────────────────────────────────────────────────────┐
│ Slot 0           │ Slot 1           │ Slot 2          │ ...│
│ [hash|key|value] │ [hash|key|value] │ [empty]         │    │
└────────────────────────────────────────────────────────────┘
```

Each slot contains:
- `long hash` — cached hash (avoids recomputing on probe)
- `long[] key` — composite key (one long per key column, pre-hashed/encoded)
- `int valueIndex` — pointer into a side array (row index for join, aggregate state index for aggregate)
- `byte occupied` — 0 = empty, 1 = occupied

**Syntax hint — open addressing insert:**
```java
public void insert(long hash, long[] key, int valueIndex) {
    int slot = (int) (hash & (capacity - 1));  // capacity must be power of 2
    while (occupied[slot] != 0) {
        if (hashes[slot] == hash && Arrays.equals(keys[slot], key)) {
            // duplicate key — handle (chain for join, update for aggregate)
            return;
        }
        slot = (slot + 1) & (capacity - 1);  // linear probe, wraps around
    }
    hashes[slot] = hash;
    keys[slot] = key;
    values[slot] = valueIndex;
    occupied[slot] = 1;
    size++;
    if (size > capacity * 0.7) resize();
}
```

**Syntax hint — open addressing probe (lookup):**
```java
public int probe(long hash, long[] key) {
    int slot = (int) (hash & (capacity - 1));
    while (occupied[slot] != 0) {
        if (hashes[slot] == hash && Arrays.equals(keys[slot], key)) {
            return values[slot];  // found
        }
        slot = (slot + 1) & (capacity - 1);
    }
    return -1;  // not found
}
```

**Key design decisions:**
- Capacity is always a power of 2 → use bitwise AND instead of modulo (faster)
- Store the full hash to short-circuit key comparison (comparing one long is faster than comparing arrays)
- Load factor threshold at 0.7 — resize by 2x when exceeded
- For composite keys, hash all key columns together using a mixing function

**Syntax hint — hashing composite keys:**
```java
public static long hashKey(long[] keyColumns) {
    long h = 0;
    for (long col : keyColumns) {
        h = h * 31 + mixBits(col);
    }
    return h;
}

private static long mixBits(long x) {
    x ^= (x >>> 33);
    x *= 0xff51afd7ed558ccdL;
    x ^= (x >>> 33);
    x *= 0xc4ceb9fe1a85ec53L;
    x ^= (x >>> 33);
    return x;
}
```

---

## Step 2: Hash Join — Build Phase

The hash join has two phases: **build** (hash the smaller table) and **probe** (stream the larger table).

**File:** `execution/src/main/java/com/vksql/execution/join/HashJoinOp.java`

**Build phase — consume the entire "build side" (smaller table) into the hash table:**

```java
// During build:
// 1. Pull all batches from the build child operator
// 2. For each row, extract join key columns, hash them
// 3. Insert into hash table: key → row index (or batch + offset)

private RawHashTable hashTable;
private List<VectorBatch> buildBatches;  // keep all build-side data in memory

public void build() {
    buildBatches = new ArrayList<>();
    int globalRowIdx = 0;
    VectorBatch batch;
    while ((batch = buildChild.next()) != null) {
        buildBatches.add(batch);
        for (int row = 0; row < batch.rowCount(); row++) {
            long[] key = extractKey(batch, row, buildKeyColumns);
            long hash = hashKey(key);
            hashTable.insert(hash, key, globalRowIdx);
            globalRowIdx++;
        }
    }
}
```

**Important:** The build side should be the **smaller** table. The query planner decides this based on stats (row count estimates from the storage footer). If you pick wrong, the hash table might not fit in memory.

**How to handle duplicate keys (1-to-many joins):**
The simple `valueIndex` approach only stores one row per key. For 1:N joins (e.g., one order has many lineitems), you need a **chain**:
- Each hash table slot stores the index of the FIRST matching row
- A separate `int[] next` array links to the NEXT matching row with the same key
- `next[rowIdx] = -1` means end of chain

```java
// Modified insert for duplicate keys:
if (hashes[slot] == hash && Arrays.equals(keys[slot], key)) {
    // Chain: prepend new row to linked list
    next[globalRowIdx] = values[slot];  // point to previous head
    values[slot] = globalRowIdx;         // new head
    return;
}
```

---

## Step 3: Hash Join — Probe Phase

Stream the larger table ("probe side") and look up each row in the hash table.

```java
// Probe phase (called from next()):
public VectorBatch next() {
    while (true) {
        VectorBatch probeBatch = probeChild.next();
        if (probeBatch == null) return null;

        // For each row in probe batch, look up in hash table
        var resultBuilder = new BatchBuilder(outputSchema);
        for (int row = 0; row < probeBatch.rowCount(); row++) {
            long[] key = extractKey(probeBatch, row, probeKeyColumns);
            long hash = hashKey(key);
            int buildRow = hashTable.probe(hash, key);
            
            // Follow the chain for all matches
            while (buildRow != -1) {
                // Emit joined row: build columns + probe columns
                emitRow(resultBuilder, buildRow, probeBatch, row);
                buildRow = next[buildRow];
            }
        }
        if (resultBuilder.rowCount() > 0) {
            return resultBuilder.build();
        }
    }
}
```

**For LEFT JOIN:** If no match found for a probe row, emit the probe row with NULLs for the build side columns.

---

## Step 4: Hash Aggregate — Build Phase

Hash Aggregate groups rows by key columns and maintains partial aggregate state per group.

**File:** `execution/src/main/java/com/vksql/execution/aggregate/HashAggregateOp.java`

**Key idea:** The hash table maps `group-by keys → aggregate state index`. The aggregate states (SUM, COUNT, MIN, MAX, AVG) live in parallel arrays.

```java
// Aggregate state arrays — one slot per group
private long[] sumStates;      // for SUM(col)
private long[] countStates;    // for COUNT(col)
private double[] sumDoubles;   // for SUM of doubles
private int numGroups = 0;

// Processing:
public void consume() {
    VectorBatch batch;
    while ((batch = child.next()) != null) {
        for (int row = 0; row < batch.rowCount(); row++) {
            long[] groupKey = extractKey(batch, row, groupByColumns);
            long hash = hashKey(groupKey);
            
            int stateIdx = hashTable.probe(hash, groupKey);
            if (stateIdx == -1) {
                // New group — allocate state
                stateIdx = numGroups++;
                hashTable.insert(hash, groupKey, stateIdx);
                initState(stateIdx);
            }
            
            // Update aggregate state
            updateState(stateIdx, batch, row);
        }
    }
}
```

**Aggregate state update — example for SUM, COUNT, AVG:**
```java
private void updateState(int stateIdx, VectorBatch batch, int row) {
    for (int i = 0; i < aggregates.length; i++) {
        switch (aggregates[i].function()) {
            case SUM -> sumStates[stateIdx] += batch.getLong(aggregates[i].column(), row);
            case COUNT -> countStates[stateIdx]++;
            case MIN -> minStates[stateIdx] = Math.min(minStates[stateIdx], 
                            batch.getLong(aggregates[i].column(), row));
            case MAX -> maxStates[stateIdx] = Math.max(maxStates[stateIdx], 
                            batch.getLong(aggregates[i].column(), row));
        }
    }
}
```

**Emit phase** — after consuming all input, iterate over all groups and output results:
```java
public VectorBatch next() {
    // Called after consume() is done
    // Iterate over hash table, emit one row per group
    // Row = group key columns + final aggregate values
}
```

**For AVG:** Store both sum and count, compute `sum / count` during emit.

---

## Step 5: Sort-Merge Join

An alternative to hash join. Useful when:
- Both inputs are already sorted (e.g., index scan)
- Data is too large to build a hash table in memory
- You need the output in sorted order anyway

**File:** `execution/src/main/java/com/vksql/execution/join/SortMergeJoinOp.java`

**Algorithm:**
1. Sort both inputs on the join key (if not already sorted)
2. Use two pointers to merge:

```java
public void execute() {
    // Assume left and right are sorted on join keys
    int l = 0, r = 0;
    
    while (l < leftRows.size() && r < rightRows.size()) {
        int cmp = compareKeys(leftRows.get(l), rightRows.get(r));
        
        if (cmp < 0) {
            l++;  // left key is smaller, advance left
        } else if (cmp > 0) {
            r++;  // right key is smaller, advance right
        } else {
            // Keys match — emit all combinations (handle duplicates)
            int rStart = r;
            while (r < rightRows.size() && 
                   compareKeys(leftRows.get(l), rightRows.get(r)) == 0) {
                emit(leftRows.get(l), rightRows.get(r));
                r++;
            }
            l++;
            // Reset right pointer for next left row with same key
            if (l < leftRows.size() && 
                compareKeys(leftRows.get(l), leftRows.get(l - 1)) == 0) {
                r = rStart;  // same left key — rescan right group
            }
        }
    }
}
```

**Vectorized version:** Instead of row-at-a-time, process in batch chunks. Collect matching row indices and build output batches.

---

## Step 6: SortOp — In-Memory and External Sort

**File:** `execution/src/main/java/com/vksql/execution/sort/SortOp.java`

The sort operator handles ORDER BY. For small data: sort in memory. For large data: external merge sort.

**In-memory sort:**
```java
public void consume() {
    // Pull all batches from child
    List<VectorBatch> allBatches = new ArrayList<>();
    VectorBatch batch;
    while ((batch = child.next()) != null) {
        allBatches.add(batch);
    }
    
    // Flatten to row indices and sort
    int totalRows = allBatches.stream().mapToInt(VectorBatch::rowCount).sum();
    int[] indices = IntStream.range(0, totalRows).toArray();
    
    // Sort indices by key columns
    IntArrays.sort(indices, (a, b) -> compareRows(allBatches, a, b, sortKeys));
}
```

**External sort — when data exceeds memory budget:**

Phase 1: **Run generation**
- Read batches until memory budget (~256MB) is reached
- Sort this chunk in memory
- Write sorted chunk ("run") to a temp file on disk
- Repeat until all input is consumed

Phase 2: **K-way merge**
- Open all sorted run files
- Use a priority queue (min-heap) to merge them
- Each entry in the heap = the current smallest row from one run
- Pop min, emit it, refill from that run

```java
// External sort pseudocode:
private List<Path> sortedRuns = new ArrayList<>();

public void generateRuns() {
    List<VectorBatch> buffer = new ArrayList<>();
    long memoryUsed = 0;
    
    VectorBatch batch;
    while ((batch = child.next()) != null) {
        buffer.add(batch);
        memoryUsed += batch.memorySize();
        
        if (memoryUsed >= MEMORY_BUDGET) {
            // Sort buffer in memory
            sortInMemory(buffer);
            // Write to temp file
            Path runFile = writeRun(buffer);
            sortedRuns.add(runFile);
            buffer.clear();
            memoryUsed = 0;
        }
    }
    // Don't forget the last partial buffer
    if (!buffer.isEmpty()) {
        sortInMemory(buffer);
        sortedRuns.add(writeRun(buffer));
    }
}

public VectorBatch next() {
    // K-way merge from sorted runs using a PriorityQueue
    return mergeNext();
}
```

**Syntax hint — PriorityQueue for k-way merge:**
```java
record MergeEntry(VectorBatch batch, int rowIdx, int runIdx) {}

PriorityQueue<MergeEntry> heap = new PriorityQueue<>(
    (a, b) -> compareKeys(a.batch(), a.rowIdx(), b.batch(), b.rowIdx(), sortKeys)
);

// Initialize: add first row from each run
for (int i = 0; i < sortedRuns.size(); i++) {
    VectorBatch first = readNextBatch(i);
    if (first != null) heap.add(new MergeEntry(first, 0, i));
}

// Merge:
while (!heap.isEmpty()) {
    MergeEntry min = heap.poll();
    emit(min.batch(), min.rowIdx());
    // Advance that run
    int nextRow = min.rowIdx() + 1;
    if (nextRow < min.batch().rowCount()) {
        heap.add(new MergeEntry(min.batch(), nextRow, min.runIdx()));
    } else {
        VectorBatch nextBatch = readNextBatch(min.runIdx());
        if (nextBatch != null) heap.add(new MergeEntry(nextBatch, 0, min.runIdx()));
    }
}
```

---

## Step 7: Benchmarking TPC-H Q1 and Q6 on ~1GB Data

Now put it all together. Generate TPC-H data at scale factor 1 (~1GB) and run two queries.

**Generate data:**
```bash
# Use dbgen (TPC-H data generator)
git clone https://github.com/electrum/tpch-dbgen.git
cd tpch-dbgen && make
./dbgen -s 1   # generates ~1GB of data in .tbl files
```

Then load into your columnar format using your file writer.

**TPC-H Q6 — Single-table scan + filter + aggregate (no join):**
```sql
SELECT SUM(l_extendedprice * l_discount) AS revenue
FROM lineitem
WHERE l_shipdate >= '1994-01-01'
  AND l_shipdate < '1995-01-01'
  AND l_discount BETWEEN 0.05 AND 0.07
  AND l_quantity < 24;
```

This tests: Scan → Filter → Project (multiply) → Aggregate (SUM). No join needed.

**Your operator pipeline for Q6:**
```
HashAggregateOp(SUM)
  └── ProjectOp(l_extendedprice * l_discount)
        └── FilterOp(date range AND discount range AND quantity < 24)
              └── ScanOp("lineitem", columns=[shipdate, discount, quantity, extendedprice])
```

**TPC-H Q1 — Single-table scan + grouped aggregate:**
```sql
SELECT l_returnflag, l_linestatus,
       SUM(l_quantity) AS sum_qty,
       SUM(l_extendedprice) AS sum_base_price,
       SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
       SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge,
       AVG(l_quantity) AS avg_qty,
       AVG(l_extendedprice) AS avg_price,
       AVG(l_discount) AS avg_disc,
       COUNT(*) AS count_order
FROM lineitem
WHERE l_shipdate <= '1998-09-02'
GROUP BY l_returnflag, l_linestatus
ORDER BY l_returnflag, l_linestatus;
```

This tests: Scan → Filter → Hash Aggregate (GROUP BY 2 cols, 8 aggregates) → Sort (ORDER BY).

**Your operator pipeline for Q1:**
```
SortOp(l_returnflag ASC, l_linestatus ASC)
  └── HashAggregateOp(group=[returnflag, linestatus], aggs=[SUM, SUM, SUM, SUM, AVG, AVG, AVG, COUNT])
        └── FilterOp(l_shipdate <= '1998-09-02')
              └── ScanOp("lineitem", columns=[all needed])
```

**Benchmark harness:**
```java
@Test
void benchmarkQ6() {
    long start = System.nanoTime();
    
    var scan = new ScanOp("lineitem", List.of("l_shipdate", "l_discount", "l_quantity", "l_extendedprice"));
    var filter = new FilterOp(scan, q6Predicate());
    var project = new ProjectOp(filter, List.of(multiply("l_extendedprice", "l_discount")));
    var aggregate = new HashAggregateOp(project, List.of(), List.of(sum("product")));
    
    VectorBatch result = aggregate.next();
    long elapsed = System.nanoTime() - start;
    
    System.out.printf("Q6 result: %.2f%n", result.getDouble(0, 0));
    System.out.printf("Q6 time: %d ms%n", elapsed / 1_000_000);
    // Target: < 2 seconds on 1GB lineitem (~6M rows)
}
```

**Performance targets (single thread, 1GB lineitem):**
| Query | Target | What It Tests |
|-------|--------|---------------|
| Q6    | < 2s   | Scan + filter + aggregate (no join, no grouping) |
| Q1    | < 5s   | Scan + filter + grouped aggregate + sort |

If you're slower, profile first:
- Q6 too slow → your scan or filter isn't vectorized (row-at-a-time penalty)
- Q1 too slow → your hash aggregate has too many cache misses (hash table too sparse or keys too wide)

---

## Order of Implementation

1. **RawHashTable** — open addressing, linear probing, power-of-2 capacity
2. **HashJoinOp** — build phase (hash smaller table), probe phase (stream larger table)
3. **HashAggregateOp** — hash on group keys, update partial aggregate states
4. **SortOp** — in-memory sort first, external sort second
5. **SortMergeJoinOp** — sort both sides, two-pointer merge
6. **TPC-H data loader** — read `.tbl` files into your columnar format
7. **Benchmark Q6** — scan + filter + aggregate pipeline
8. **Benchmark Q1** — scan + filter + hash aggregate + sort pipeline

Start with RawHashTable. Everything else depends on it.

---

## Hash Table Design Deep Dive

```
┌─────────────────────────────────────────────────────────────┐
│                    RawHashTable Layout                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  long[] hashes      ← cached hash per slot (avoid rehash)  │
│  long[][] keys      ← composite key arrays                 │
│  int[] values       ← payload (row idx or state idx)       │
│  byte[] occupied    ← 0=empty, 1=occupied                  │
│                                                             │
│  Invariants:                                                │
│  - capacity is always power of 2                            │
│  - size / capacity ≤ 0.7 (load factor)                     │
│  - slot = hash & (capacity - 1) (bitwise mod)              │
│                                                             │
│  Resize:                                                    │
│  - Allocate 2x arrays                                       │
│  - Re-insert all occupied slots (re-probe with new mask)    │
│  - Old arrays become garbage                                │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  Memory estimate:                                           │
│  - 1M groups, 2 key cols: ~24MB (hashes + keys + values)   │
│  - 10M build rows, 1 key col: ~150MB                       │
│  - Budget: hash table should fit in ~25% of heap            │
└─────────────────────────────────────────────────────────────┘
```

**Alternative: Swiss Table style (SIMD-friendly groups)**
For even better performance, you could group slots into 16-slot "groups" with a metadata byte per slot (empty/deleted/H2 hash bits). This enables SIMD probing of 16 slots at once. Out of scope for now, but worth knowing exists.

---

## Common Mistakes

1. **Using java.util.HashMap for the join/aggregate hash table.** It boxes every key/value into objects, allocates Entry nodes, and has terrible cache behavior. You need a flat, primitive-based table.

2. **Forgetting to handle duplicate keys in Hash Join.** If the build side has 5 rows with key=42, a single probe for key=42 must return ALL 5. Use the chaining (`next[]` array) approach.

3. **Not picking the smaller side for build.** If you hash the 6M-row lineitem table instead of the 150K-row orders table, your hash table is 40x larger and might not fit in memory.

4. **Power-of-2 capacity but using modulo (`%`).** Use `hash & (capacity - 1)` — modulo is slow and gives wrong results for negative hash values.

5. **Forgetting to flush the last run in external sort.** Same pattern as "flush last row group" from Week 1. The buffer at the end might not hit the memory threshold but still has data.

6. **Not resetting the right pointer in sort-merge join.** When the left side has duplicate keys, you must re-scan the matching right-side group for each left row.

7. **Computing AVG as a running average instead of sum/count.** Running average loses precision with floating point. Always store sum and count separately, divide at emit time.

8. **External sort temp files not cleaned up.** Use `deleteOnExit()` or explicit cleanup in `close()`.

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Open addressing | RawHashTable — all slots in one array |
| Linear probing | Insert/probe — walk forward on collision |
| Build vs probe | Hash Join — asymmetric phases |
| Partial aggregation | Hash Aggregate — accumulate state incrementally |
| External merge sort | SortOp — spill to disk, k-way merge |
| Two-pointer merge | Sort-Merge Join — sorted inputs, linear scan |
| Cache-conscious design | Flat arrays vs pointer-chasing |

---

## When You're Done

- ✅ RawHashTable passes unit tests (insert, probe, resize, duplicate keys)
- ✅ HashJoinOp correctly joins two tables on key columns (including 1:N)
- ✅ HashAggregateOp groups and aggregates with correct results
- ✅ SortOp sorts small and large (spill-to-disk) datasets
- ✅ SortMergeJoinOp produces same results as HashJoinOp
- ✅ TPC-H Q6 runs in < 2 seconds on 1GB lineitem
- ✅ TPC-H Q1 runs in < 5 seconds on 1GB lineitem
- ✅ All tests pass: `./gradlew :execution:test`

**Next week:** Query planning — cost-based join ordering, predicate pushdown, and choosing between hash join vs sort-merge join based on table sizes.
