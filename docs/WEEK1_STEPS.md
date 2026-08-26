# Week 1: Columnar Storage — Fixed-Width Types

## What You're Building

A binary file format that stores data **column by column** instead of row by row. By the end of this week, you can write 10 million rows of `(int, long, double)` to a file and read back the metadata.

## Why Columnar?

Row storage: `[id=1, name="alice", age=30] [id=2, name="bob", age=25] ...`
Column storage: `[1, 2, 3, ...] [alice, bob, charlie, ...] [30, 25, 28, ...]`

For analytics (SUM, AVG, COUNT, WHERE), you usually only need 1-2 columns out of 50. Columnar lets you read ONLY those columns from disk. Also, same-type values compress much better together.

---

## Step 0: Project Setup

```bash
cd /Users/vamsi.2197428/Projects/vksql
gradle init --type java-library --dsl kotlin --java-version 21
```

Then set up the multi-module structure. For now you only need the `storage` module.

**Files to create:**
- `settings.gradle.kts` — declare `storage` as a subproject
- `storage/build.gradle.kts` — dependencies (just JUnit 5 for now)

---

## Step 1: Define Your Data Types

**File:** `storage/src/main/java/com/vksql/storage/format/DataType.java`

An enum with 3 types: INT32, INT64, FLOAT64. That's it for now.

**Syntax hint — enums:**
```java
public enum Color {
    RED, GREEN, BLUE;
}
```

---

## Step 2: Define Schema and Column Metadata

**File:** `storage/src/main/java/com/vksql/storage/format/ColumnDescriptor.java`

A record that holds: column name (String), type (DataType), column index (int).

**File:** `storage/src/main/java/com/vksql/storage/format/Schema.java`

A record wrapping a `List<ColumnDescriptor>`. Add helper methods to get column count and get column by index.

**Syntax hint — records:**
```java
public record Point(int x, int y) {
    public double distanceTo(Point other) { ... }
}
```

**Syntax hint — List.of:**
```java
var items = List.of("a", "b", "c");
items.size();    // 3
items.get(0);    // "a"
```

---

## Step 3: Understand the File Layout (on paper first)

Before writing code, understand what the binary file looks like:

```
[Magic: 4 bytes "VKQL"]
[Row Group 0]
  [Column 0 data: raw int32 values, page by page]
  [Column 1 data: raw int64 values, page by page]
  [Column 2 data: raw float64 values, page by page]
[Row Group 1]
  ...
[Footer: schema + offsets + stats]
[Footer length: 4 bytes]
[Magic: 4 bytes "VKQL"]
```

Key terms:
- **Page**: A chunk of ~64KB of values for one column. The smallest unit of I/O.
- **Column Chunk**: All pages for one column within one row group.
- **Row Group**: A horizontal slice of the table (e.g., 1M rows). Contains one column chunk per column.
- **Footer**: Metadata at the end of file — tells you where everything is without reading the whole file.

---

## Step 4: Write a Page

**File:** `storage/src/main/java/com/vksql/storage/format/Page.java`

A record holding: number of values, uncompressed size, and the raw byte array.

**File:** `storage/src/main/java/com/vksql/storage/writer/PageWriter.java`

A class that:
- Has a `ByteBuffer` that accumulates values
- Has methods to write int/long/double
- Has an `isFull()` check (buffer position >= 64KB)
- Has a `flush()` that returns a `Page` and resets the buffer

**Syntax hint — ByteBuffer:**
```java
ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
buf.putInt(42);          // writes 4 bytes
buf.putLong(100L);       // writes 8 bytes
buf.putDouble(3.14);     // writes 8 bytes
buf.position();          // how many bytes written so far
buf.array();             // get underlying byte[]
buf.clear();             // reset position to 0
```

**Syntax hint — copying part of an array:**
```java
byte[] trimmed = Arrays.copyOf(buf.array(), buf.position());
```

---

## Step 5: Write a Column Chunk

**File:** `storage/src/main/java/com/vksql/storage/writer/ColumnChunkWriter.java`

A class that:
- Owns a `PageWriter`
- Collects finished `Page` objects into a list
- Tracks min/max values as it receives data
- Has a `finish()` method that flushes any remaining data and returns the pages + stats

**Think about:** How do you track min/max for different types? One approach: cast everything to `long` for stats (works for int and long, lossy for double — ok for now).

**Syntax hint — tracking min/max:**
```java
long minValue = Long.MAX_VALUE;
long maxValue = Long.MIN_VALUE;
// on each value:
minValue = Math.min(minValue, value);
maxValue = Math.max(maxValue, value);
```

---

## Step 6: Write a Row Group

**File:** `storage/src/main/java/com/vksql/storage/writer/RowGroupWriter.java`

A class that:
- Has one `ColumnChunkWriter` per column
- Receives whole rows, splits values to the right column writer
- Tracks row count
- Has a `isFull()` (row count >= 1,000,000)
- Has a `finish()` that finishes all column writers

**Syntax hint — varargs:**
```java
public void writeRow(Object... values) {
    // values[0], values[1], ...
    // cast: (int) values[0], (long) values[1]
}
```

**Syntax hint — switch on enum:**
```java
switch (type) {
    case INT32 -> doSomething();
    case INT64 -> doSomethingElse();
    case FLOAT64 -> doAnother();
}
```

---

## Step 7: Write the File (FileWriter)

**File:** `storage/src/main/java/com/vksql/storage/writer/VksqlFileWriter.java`

A class that implements `Closeable` and:
1. On construction: opens an OutputStream, writes magic "VKQL", creates first RowGroupWriter
2. `writeRow(...)`: passes to current row group. If row group full → flush it and start new one
3. `flushRowGroup()`: finishes the row group, writes all pages to output stream, records metadata (offsets, stats)
4. `close()`: flushes remaining row group, writes footer, writes footer length (4 bytes), writes magic, closes stream

**Syntax hint — writing to file:**
```java
OutputStream out = new BufferedOutputStream(Files.newOutputStream(path));
out.write(bytes);              // write byte array
out.write("VKQL".getBytes()); // write magic
out.close();
```

**Syntax hint — Closeable + try-with-resources:**
```java
public class MyWriter implements Closeable {
    public void close() throws IOException { ... }
}
// usage:
try (var w = new MyWriter(path)) {
    w.writeRow(...);
} // close() called automatically
```

**Syntax hint — tracking bytes written:**
```java
long bytesWritten = 0;
// after each out.write(data):
bytesWritten += data.length;
```

---

## Step 8: Define Footer Metadata Classes

Records you need:
- `ColumnChunkMetadata` — column name, type, file offset, total size, num values, min, max
- `RowGroupMetadata` — row count, list of column chunk metadatas
- `FileFooter` — schema, list of row group metadatas

Put these in `storage/src/main/java/com/vksql/storage/format/`.

---

## Step 9: Serialize/Deserialize the Footer

**File:** `storage/src/main/java/com/vksql/storage/format/FooterSerializer.java`

Write the footer as structured binary:
- Number of columns → for each: name length, name bytes, type ordinal
- Number of row groups → for each: row count, then per-column: offset, size, numValues, min, max

**Syntax hint — DataOutputStream (writing structured binary):**
```java
var baos = new ByteArrayOutputStream();
var dos = new DataOutputStream(baos);
dos.writeInt(42);
dos.writeLong(100L);
dos.writeUTF("hello");    // writes length-prefixed string
byte[] result = baos.toByteArray();
```

**Syntax hint — DataInputStream (reading it back):**
```java
var bais = new ByteArrayInputStream(bytes);
var dis = new DataInputStream(bais);
int x = dis.readInt();
long y = dis.readLong();
String s = dis.readUTF();
```

---

## Step 10: Read the Footer (FileReader)

**File:** `storage/src/main/java/com/vksql/storage/reader/VksqlFileReader.java`

A class that:
1. Opens the file with `RandomAccessFile` (supports seeking)
2. Reads footer by seeking to end of file
3. Exposes `getFooter()`

The reading logic:
- File ends with: `[footer bytes][footer length: 4 bytes][magic: 4 bytes]`
- So seek to `fileLength - 8`, read 4 bytes as int (footer length), read 4 bytes (verify magic)
- Then seek to `fileLength - 8 - footerLength`, read that many bytes, deserialize

**Syntax hint — RandomAccessFile:**
```java
var raf = new RandomAccessFile(path.toFile(), "r");
raf.length();          // file size in bytes
raf.seek(position);    // move to byte offset
raf.readInt();         // read 4 bytes as int
raf.readFully(buf);    // read exactly buf.length bytes
raf.close();
```

---

## Order of Implementation

1. `DataType` enum
2. `ColumnDescriptor` + `Schema` records
3. `Page` record
4. `PageWriter` class
5. Metadata records (`ColumnChunkMetadata`, `RowGroupMetadata`, `FileFooter`)
6. `ColumnChunkWriter` — uses PageWriter, tracks stats
7. `RowGroupWriter` — holds multiple ColumnChunkWriters
8. `FooterSerializer` — serialize/deserialize footer to bytes
9. `VksqlFileWriter` — orchestrates everything, writes to disk
10. `VksqlFileReader` — reads footer from end of file
11. Run `StorageEngineTest` — make it pass

---

## Common Mistakes to Avoid

1. **Don't use Java serialization** (`Serializable`/`ObjectOutputStream`). Write raw bytes with `DataOutputStream` or `ByteBuffer`.
2. **Don't store the footer at the beginning.** You don't know the offsets until you've written all the data. Footer goes at the end.
3. **Don't forget to flush the last row group.** If you write 2.5M rows, you have 2 full groups + 1 partial. The partial still needs to be written in `close()`.
4. **Use `try-with-resources`** for the file writer.

---

## When You're Done With This

You should be able to:
- ✅ Write a 10M row file with 3 columns
- ✅ Read back the footer and see 10 row groups with correct stats
- ✅ File size should be ~10M * (4 + 8 + 8) = ~200MB (uncompressed, no encoding yet)
- ✅ Know exactly where each column chunk lives in the file (offset from footer)
- ✅ All tests in `StorageEngineTest.java` pass

**Next week:** Add STRING type, null bitmaps, and start reading actual column data (not just footer).
