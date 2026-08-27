# Week 3: Encoding + Compression

## What You're Building

A pluggable encoding and compression layer that sits between raw column data and the on-disk page format. By the end, your 200MB test file from Week 1 should shrink to ~30-50MB depending on data patterns — without losing a single bit of information.

---

## Why Encoding + Compression?

Raw storage is wasteful. Consider a `country` column with 10M rows but only 50 unique values. Without encoding, you store the full string 10M times. With **dictionary encoding**, you store 50 strings + 10M tiny integer indices.

The pipeline is:

```
Raw Values → Encoding (structural transformation) → Compression (byte-level shrinking) → Disk
```

Encoding exploits **data patterns** (repetition, sorting, monotonicity). Compression exploits **byte-level redundancy** (Huffman, LZ77). They compose — encoding first makes compression even more effective.

---

## Step 1: Dictionary Encoding (STRING columns)

### Concept

Instead of storing `["USA", "USA", "Canada", "USA", "Canada", ...]`, store:
```
Dictionary: [0 → "USA", 1 → "Canada"]
Indices:    [0, 0, 1, 0, 1, ...]
```

The indices are fixed-width integers (1, 2, or 4 bytes depending on dictionary size). For 50 unique countries, you only need 1 byte per index instead of ~6 bytes per string.

### When to use

- Low-cardinality STRING columns (country, status, category)
- Threshold: if `unique_count / total_count < 0.4`, dictionary encode
- NOT for high-cardinality (emails, UUIDs) — dictionary grows as large as the data

### Page layout (dictionary-encoded)

```
[encoding_type: 1 byte = 0x01 for DICTIONARY]
[dict_size: 4 bytes (int)]
[dict entries: for each → string_length (2 bytes) + string_bytes]
[num_indices: 4 bytes]
[index_width: 1 byte (1, 2, or 4)]
[indices: num_indices * index_width bytes]
```

### Syntax hint — building a dictionary:

```java
Map<String, Integer> dict = new HashMap<>();
List<Integer> indices = new ArrayList<>();

for (String value : values) {
    int idx = dict.computeIfAbsent(value, k -> dict.size());
    indices.add(idx);
}
```

### Syntax hint — choosing index width:

```java
int dictSize = dict.size();
int indexWidth;
if (dictSize <= 255) indexWidth = 1;        // Byte.UNSIGNED_MAX
else if (dictSize <= 65535) indexWidth = 2;  // Short.UNSIGNED_MAX
else indexWidth = 4;
```

### Think about

- The dictionary is stored **per page**, not per file. Each page is self-contained.
- If the dictionary overflows (too many unique values mid-page), fall back to plain encoding for that page.

---

## Step 2: Run-Length Encoding (INT columns)

### Concept

Instead of `[7, 7, 7, 7, 7, 3, 3, 3]`, store:
```
[(value=7, count=5), (value=3, count=3)]
```

8 integers → 2 pairs. Massive savings when data is sorted or has long runs of repeated values.

### When to use

- Sorted columns (e.g., foreign keys after a sort)
- Status columns with repeated values
- Columns with low cardinality that have been clustered/sorted
- NOT for random/unique data — RLE of unique values is *larger* than plain encoding

### Page layout (RLE-encoded)

```
[encoding_type: 1 byte = 0x02 for RLE]
[num_runs: 4 bytes (int)]
[runs: for each → value (4 bytes int) + count (4 bytes int)]
```

### Syntax hint — encoding:

```java
List<long[]> runs = new ArrayList<>();  // each: [value, count]
long currentVal = values[0];
int currentCount = 1;

for (int i = 1; i < values.length; i++) {
    if (values[i] == currentVal) {
        currentCount++;
    } else {
        runs.add(new long[]{currentVal, currentCount});
        currentVal = values[i];
        currentCount = 1;
    }
}
runs.add(new long[]{currentVal, currentCount});  // don't forget the last run
```

### Syntax hint — decoding:

```java
int[] decoded = new int[totalValues];
int pos = 0;
for (long[] run : runs) {
    Arrays.fill(decoded, pos, pos + (int) run[1], (int) run[0]);
    pos += (int) run[1];
}
```

---

## Step 3: Delta Encoding (monotonically increasing values)

### Concept

Instead of `[1000, 1001, 1003, 1006, 1010]`, store:
```
Base: 1000
Deltas: [0, 1, 2, 3, 4]
```

If values always increase by 1, deltas are all 1 — then RLE on top of deltas gives incredible compression. Even without RLE, deltas are small numbers that compress well with general-purpose compressors.

### When to use

- Timestamps (monotonically increasing, often with fixed intervals)
- Auto-increment IDs
- Sorted integer/long columns
- NOT for random or non-monotonic data

### Page layout (delta-encoded)

```
[encoding_type: 1 byte = 0x03 for DELTA]
[base_value: 8 bytes (long)]
[num_deltas: 4 bytes]
[bit_width: 1 byte — min bits needed for max delta]
[packed_deltas: bit-packed array]
```

### Syntax hint — computing deltas:

```java
long base = values[0];
long[] deltas = new long[values.length];
deltas[0] = 0;
long maxDelta = 0;
for (int i = 1; i < values.length; i++) {
    deltas[i] = values[i] - values[i - 1];
    maxDelta = Math.max(maxDelta, deltas[i]);
}
```

### Syntax hint — choosing bit width:

```java
int bitWidth = 64 - Long.numberOfLeadingZeros(maxDelta);
if (bitWidth == 0) bitWidth = 1;  // all deltas are 0, still need 1 bit
```

### Bit-packing (simplified approach)

For your first implementation, just store deltas as the smallest fixed-width integer that fits:
- All deltas ≤ 255 → 1 byte each
- All deltas ≤ 65535 → 2 bytes each
- Otherwise → 4 or 8 bytes each

True bit-packing (e.g., 5-bit values) is an optimization for later.

---

## Step 4: Compression (Snappy + Zstd)

### Concept

Encoding changes the **structure** of data. Compression shrinks the **bytes**. Apply compression *after* encoding — encoded data compresses better because it has more redundancy.

Two compressors to support:
- **Snappy** — fast, moderate ratio (~2-4x). Good for hot path reads.
- **Zstd** — slower, better ratio (~5-10x). Good for cold storage.

### Add dependencies

In `storage/build.gradle.kts`:
```kotlin
dependencies {
    implementation("org.xerial.snappy:snappy-java:1.1.10.5")
    implementation("com.github.luben:zstd-jni:1.5.6-4")
}
```

### Syntax hint — Snappy:

```java
import org.xerial.snappy.Snappy;

byte[] compressed = Snappy.compress(rawBytes);
byte[] decompressed = Snappy.uncompress(compressed);
```

### Syntax hint — Zstd:

```java
import com.github.luben.zstd_jni.Zstd;

byte[] compressed = Zstd.compress(rawBytes);
byte[] decompressed = Zstd.decompress(compressed, (int) Zstd.decompressedSize(compressed));
```

### Page layout with compression

```
[encoding_type: 1 byte]
[compression_type: 1 byte — 0x00=NONE, 0x01=SNAPPY, 0x02=ZSTD]
[uncompressed_size: 4 bytes]
[compressed_size: 4 bytes]
[compressed_data: compressed_size bytes]
```

The reader:
1. Reads compression type
2. Reads compressed bytes
3. Decompresses to get the encoded data
4. Decodes to get raw values

### Think about

- Compression is applied **per page** — each page is independently decompressible
- Store `uncompressed_size` so the decompressor knows how much memory to allocate
- If compression makes data *larger* (happens with random data), fall back to NONE for that page

---

## Step 5: How to Choose Encoding (Heuristics)

### The Encoding Selector

**File:** `storage/src/main/java/com/vksql/storage/encoding/EncodingSelector.java`

Before writing a page, sample the data and pick the best encoding:

```java
public enum Encoding {
    PLAIN,       // no encoding — raw values
    DICTIONARY,  // low-cardinality strings
    RLE,         // sorted/repetitive integers
    DELTA        // monotonically increasing values
}
```

### Decision logic:

```
Is it a STRING column?
  → Compute cardinality ratio = unique_count / total_count
  → If ratio < 0.4 → DICTIONARY
  → Else → PLAIN (strings don't benefit from RLE/delta)

Is it an INT/LONG column?
  → Is data sorted and monotonically increasing?
    → Yes → DELTA
  → Are there long runs of repeated values?
    → Compute run_count / total_count
    → If ratio < 0.3 → RLE
  → Else → PLAIN
```

### Syntax hint — sampling:

```java
// Don't analyze all values — sample first N
int sampleSize = Math.min(1024, values.length);
Set<Object> uniques = new HashSet<>();
boolean monotonic = true;
int runCount = 1;

for (int i = 0; i < sampleSize; i++) {
    uniques.add(values[i]);
    if (i > 0) {
        if (!values[i].equals(values[i-1])) runCount++;
        if (values[i] < values[i-1]) monotonic = false;  // for numeric
    }
}

double cardinalityRatio = (double) uniques.size() / sampleSize;
double runRatio = (double) runCount / sampleSize;
```

### Think about

- The selector runs on the first batch of values for a page
- If the heuristic picks wrong, the page still works — it's just bigger
- You can always fall back to PLAIN if an encoding fails mid-page (e.g., dictionary overflow)

---

## Step 6: Wire It All Together

### Encoding/Decoding Interface

```java
public interface PageEncoder {
    byte[] encode(Object values, int count);  // int[], long[], String[]
}

public interface PageDecoder {
    Object decode(byte[] data, int expectedCount);
}
```

### Where encoding/compression plugs in

Modify your existing `PageWriter.flush()`:

```
Before (Week 2):  raw bytes → Page → disk
After (Week 3):   raw values → encode → compress → Page → disk
```

Modify your existing `ColumnReader` page reading:

```
Before:  disk → raw bytes → values
After:   disk → decompress → decode → values
```

### Column metadata update

Add encoding and compression type to `ColumnChunkMetadata` so the reader knows how to decode:

```java
public record ColumnChunkMetadata(
    String name, DataType type, long offset, long size,
    int numValues, long min, long max,
    Encoding encoding, Compression compression  // ← new
) {}
```

---

## Step 7: Benchmarking

### The benchmark test

Write a benchmark that generates realistic data and measures file sizes:

```java
@Test
void compressionRatioBenchmark() throws Exception {
    Schema schema = new Schema(List.of(
        new ColumnDescriptor("id", DataType.INT64, 0),          // monotonic → delta
        new ColumnDescriptor("country", DataType.STRING, 1),    // low-cardinality → dict
        new ColumnDescriptor("status", DataType.INT32, 2),      // repetitive → RLE
        new ColumnDescriptor("amount", DataType.FLOAT64, 3)     // random → plain
    ));

    int numRows = 1_000_000;
    String[] countries = {"USA", "Canada", "UK", "Germany", "France", "Japan"};
    int[] statuses = {0, 0, 0, 1, 1, 2};  // mostly 0s

    // Write WITHOUT encoding/compression
    Path plainFile = writePlainFile(schema, numRows, countries, statuses);

    // Write WITH encoding + Snappy
    Path snappyFile = writeEncodedFile(schema, numRows, countries, statuses, Compression.SNAPPY);

    // Write WITH encoding + Zstd
    Path zstdFile = writeEncodedFile(schema, numRows, countries, statuses, Compression.ZSTD);

    long plainSize = Files.size(plainFile);
    long snappySize = Files.size(snappyFile);
    long zstdSize = Files.size(zstdFile);

    System.out.printf("Plain:  %,d bytes%n", plainSize);
    System.out.printf("Snappy: %,d bytes (%.1fx)%n", snappySize, (double) plainSize / snappySize);
    System.out.printf("Zstd:   %,d bytes (%.1fx)%n", zstdSize, (double) plainSize / zstdSize);

    // Verify compression actually helps
    assertTrue(snappySize < plainSize * 0.5, "Snappy should achieve at least 2x compression");
    assertTrue(zstdSize < plainSize * 0.4, "Zstd should achieve at least 2.5x compression");
    assertTrue(zstdSize < snappySize, "Zstd should be smaller than Snappy");
}
```

### What to measure

| Metric | How |
|--------|-----|
| File size | `Files.size(path)` |
| Compression ratio | `plain_size / compressed_size` |
| Write throughput | `rows / write_time_ms * 1000` |
| Read throughput | `rows / read_time_ms * 1000` |

### Expected results (rough targets for 1M rows)

| Encoding | File Size | Ratio |
|----------|-----------|-------|
| Plain | ~28 MB | 1.0x |
| Encoding only (no compression) | ~12 MB | ~2.3x |
| Encoding + Snappy | ~8 MB | ~3.5x |
| Encoding + Zstd | ~5 MB | ~5.5x |

---

## Order of Implementation

1. Define `Encoding` enum (PLAIN, DICTIONARY, RLE, DELTA)
2. Define `Compression` enum (NONE, SNAPPY, ZSTD)
3. Implement `DictionaryEncoder` / `DictionaryDecoder`
4. Implement `RleEncoder` / `RleDecoder`
5. Implement `DeltaEncoder` / `DeltaDecoder`
6. Implement `Compressor` wrapper (Snappy + Zstd)
7. Implement `EncodingSelector` (heuristics)
8. Modify `PageWriter` to apply encoding before writing
9. Modify `ColumnReader` to decompress + decode when reading
10. Update `ColumnChunkMetadata` with encoding/compression info
11. Update footer serialization for new metadata fields
12. Write roundtrip tests (encode → compress → decompress → decode = original)
13. Run benchmark test — verify compression ratio

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Information theory | Why encoding + compression compose well |
| Bit-packing | Delta encoding with variable-width integers |
| Space-time tradeoff | Snappy (fast, big) vs Zstd (slow, small) |
| Heuristic design | Choosing encoding without scanning all data |
| Layered architecture | Encoding and compression as independent, composable stages |

---

## Common Mistakes

1. **Compressing before encoding** — always encode first. Dictionary-encoded data (small integers) compresses much better than raw strings.
2. **Storing dictionary per file instead of per page** — if a page is the unit of I/O, the dictionary must be in the page. Otherwise you can't read one page without reading the whole file.
3. **Not handling the "compression makes it bigger" case** — for random data, compressed output can be larger than input. Check and fall back to NONE.
4. **Forgetting to store uncompressed size** — Zstd's `decompress()` needs to know the output buffer size. Store it in the page header.
5. **Off-by-one in RLE** — don't forget the last run after the loop ends.
6. **Delta encoding negative deltas** — if data isn't sorted, deltas can be negative. Either reject non-monotonic data or use zigzag encoding (`(n << 1) ^ (n >> 63)`).
7. **Using compression level 0 for Zstd** — the default level (3) is fine. Level 0 means "no compression" in some implementations.
8. **Not verifying roundtrip correctness** — always test: `decode(encode(data)) == data`. Do this before benchmarking.

---

## When You're Done

- ✅ Dictionary encoding works for STRING columns (encode + decode roundtrip)
- ✅ RLE works for repetitive INT columns
- ✅ Delta encoding works for monotonically increasing values
- ✅ Snappy and Zstd compression applied per page
- ✅ Encoding is chosen automatically via heuristics
- ✅ Benchmark shows at least 3x compression with Snappy, 5x with Zstd on realistic data
- ✅ All roundtrip tests pass — no data loss

**Next week:** Query execution engine — scan operators, filter pushdown, and vectorized batch processing.
