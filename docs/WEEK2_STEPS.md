# Week 2: Variable-Length Types + Null Bitmaps

## What You're Building

Extend your storage engine to handle STRING columns and NULL values. By the end, you can write tables with mixed types including strings and nulls, and read actual column data back (not just metadata).

---

## Why This Matters

Week 1 only handled fixed-width types (int, long, double) — every value is the same size. But real data has:
- **Strings** — variable length. "a" is 1 byte, "hello world" is 11 bytes.
- **Nulls** — missing values. You can't just store 0 because 0 might be a valid value.

Without these, your engine can't handle real SQL tables.

---

## Step 1: Add STRING to DataType

Your `DataType` enum needs a new entry. But STRING is different — it doesn't have a fixed byte width.

Think about: how does the reader know where one string ends and the next begins?

---

## Step 2: Understand String Encoding

Two common approaches:

**Option A: Length-prefixed** — before each string, write its length:
```
[len=5][hello][len=3][bob][len=11][hello world]
```
Problem: to read string #1000, you must scan through all 999 before it (no random access).

**Option B: Offset array** — store all offsets first, then all string bytes:
```
[offset_0=0][offset_1=5][offset_2=8][end=19]  ← offsets (fixed-width, random access!)
[hellobobhello world]                          ← string data packed together
```

To read string #1: start at offset_1 (byte 5), end at offset_2 (byte 8) → "bob"

**Use Option B.** It gives you random access to any string by index — just look up two consecutive offsets.

**Syntax hint — offset encoding layout for a page:**
```
[num_strings: 4 bytes (int)]
[offset_0: 4 bytes][offset_1: 4 bytes]...[offset_n: 4 bytes][end_offset: 4 bytes]
[string_bytes: variable]
```

Note: you store `num_strings + 1` offsets (the extra one marks the end of the last string).

---

## Step 3: Modify PageWriter for Strings

Your current PageWriter uses a single `ByteBuffer` for fixed-width values. For strings, you need to track:
- A list of string bytes (or a growing ByteBuffer)
- An offsets list (grows by 1 per string)

Two approaches:
1. Add string-specific handling inside the existing `PageWriter`
2. Create a separate `StringPageWriter`

Either works. The key difference: `isFull()` for strings checks the **total accumulated size** (offsets + string data), not just a value count.

**Think about:** When you flush a string page, what does the byte array look like? You need to pack the offsets + data together into one `byte[]` for the `Page` record.

---

## Step 4: Modify ColumnChunkWriter for Strings

Add a `writeString(String value)` method. It should:
- Pass the string to the page writer
- Track min/max (for strings: lexicographic comparison — or skip min/max for strings for now)
- Flush page when full

**Hint for min/max on strings:** You could store the min/max as the first/last string lexicographically, but that's complex with `long` fields. For now, just skip stats for STRING columns (set min=0, max=0) — you can add it later.

---

## Step 5: Understand Null Bitmaps

A null bitmap is 1 bit per value. Bit = 1 means non-null, bit = 0 means null.

For 8 values: `[1,1,0,1,1,1,0,1]` → packed into 1 byte: `0b10111011` = `0xBB`

For 1M values: you need `1M / 8 = 125KB` for the bitmap — tiny compared to the data itself.

**Why a bitmap and not a flag per value?**
- Flag per value: 1 byte per value = 1MB for 1M values
- Bitmap: 1 bit per value = 125KB for 1M values → 8x less overhead

**Syntax hint — setting/getting bits:**
```java
// Set bit at position i
bitmap[i / 8] |= (1 << (i % 8));

// Check if bit at position i is set (non-null)
boolean isNonNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;
```

---

## Step 6: Add Null Bitmap to PageWriter

When writing values, you also need to track which positions are null:
- Keep a `byte[]` bitmap that grows with values
- Add a `writeNull()` method (increments count but doesn't write data, leaves bit as 0)
- On `flush()`, include the bitmap in the page data

**Page layout with nulls:**
```
[null_bitmap_size: 4 bytes]
[null_bitmap: ceil(numValues / 8) bytes]
[values data: same as before]
```

The reader reads the bitmap first, then knows which values to skip when reading data.

---

## Step 7: Modify RowGroupWriter

Add a way to pass nulls. The simplest: if the value in `writeRow(...)` is `null`, call `writeNull()` on the column writer instead of `writeInt32`/etc.

```java
// In writeRow:
if (values[i] == null) {
    chunkWriters.get(i).writeNull();
} else {
    switch (type) { ... }
}
```

---

## Step 8: Read Column Data (not just footer)

Until now, your reader only reads the footer. Now add the ability to read actual column data:

1. Use the footer to find the offset and size of a column chunk
2. Seek to that offset
3. Read the pages
4. Decode values from bytes back into int/long/double/String arrays

This is the **ColumnReader** — the inverse of ColumnChunkWriter.

**File:** `storage/src/main/java/com/vksql/storage/reader/ColumnReader.java`

A class that:
- Takes a `RandomAccessFile`, a `ColumnChunkMetadata`, and the `DataType`
- Reads pages from disk
- Decodes bytes back into values
- Returns them as arrays (e.g., `int[]`, `long[]`, `String[]`)
- Also returns the null bitmap

---

## Step 9: Page Decoding

For fixed-width types, reading a page back is simple:
```java
ByteBuffer buf = ByteBuffer.wrap(pageData);
int[] values = new int[numValues];
for (int i = 0; i < numValues; i++) {
    values[i] = buf.getInt();
}
```

For strings with offset encoding:
```java
// Read offsets
int numStrings = buf.getInt();
int[] offsets = new int[numStrings + 1];
for (int i = 0; i <= numStrings; i++) {
    offsets[i] = buf.getInt();
}
// Read string data
String[] values = new String[numStrings];
byte[] stringData = new byte[remaining bytes];
buf.get(stringData);
for (int i = 0; i < numStrings; i++) {
    values[i] = new String(stringData, offsets[i], offsets[i+1] - offsets[i]);
}
```

---

## Step 10: Write Tests First

```java
@Test
void writeAndReadStrings() {
    // Write rows with a STRING column
    // Read back the column data
    // Verify strings match
}

@Test
void writeAndReadNulls() {
    // Write rows where some values are null
    // Read back and verify nulls are in the right positions
}

@Test
void mixedTypesWithNulls() {
    // Schema: (INT32, STRING, FLOAT64) with some nulls
    // Write, read, verify everything
}
```

---

## Order of Implementation

1. Add `STRING` to `DataType`
2. Implement string writing in PageWriter (offset encoding)
3. Add `writeString()` to ColumnChunkWriter
4. Implement null bitmap tracking in PageWriter
5. Add `writeNull()` to ColumnChunkWriter
6. Update RowGroupWriter to handle nulls
7. Update serializer/deserializer if schema changes needed
8. Build ColumnReader — reads pages, decodes values
9. Add string page decoding
10. Add null bitmap decoding
11. Tests pass

---

## Concepts You'll Learn

| Concept | Where You'll Hit It |
|---------|-------------------|
| Offset encoding | String pages — random access to variable-length data |
| Bit manipulation | Null bitmaps — packing 8 booleans into 1 byte |
| Page layout design | Deciding what goes where in a page's byte array |
| Decode/encode symmetry | Every write format must have an exact inverse read |

---

## Common Mistakes

1. **Off-by-one in offsets** — you need `numStrings + 1` offsets (the extra one marks the end of the last string). Without it, you don't know the length of the last string.
2. **Null bitmap size** — it's `ceil(numValues / 8)` bytes, not `numValues / 8`. For 9 values, you need 2 bytes, not 1.
3. **Don't store data for null values** — if value is null, only the bitmap is updated. No bytes written to the data section. This means when reading, you skip nulls in the data stream.
4. **ByteBuffer position matters** — after reading offsets, the buffer position has advanced. The string data starts at the current position, not at offset 0.

---

## When You're Done

- ✅ Can write tables with STRING columns
- ✅ Can write and read null values correctly
- ✅ Can read actual column data back (not just footer metadata)
- ✅ Mixed-type schema with nulls roundtrips correctly
- ✅ All new tests pass

**Next week:** Encoding schemes (dictionary, RLE, delta) + compression (snappy/zstd).
