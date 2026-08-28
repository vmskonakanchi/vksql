package com.vksql.storage.reader;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.vksql.storage.format.*;

/**
 * Memory-mapped file reader using Java 21 Panama MemorySegment.
 *
 * Instead of reading bytes into heap arrays (copies data from kernel → JVM heap),
 * this maps the file directly into the process address space.
 * The OS handles paging — no explicit I/O calls, no GC pressure.
 *
 * Benefits:
 * - Zero-copy: data stays in OS page cache, no heap allocation for raw data
 * - OS handles caching: frequently accessed pages stay in RAM automatically
 * - No GC pressure: MemorySegment is off-heap
 */
public class MappedFileReader {
    // Unaligned layouts — our file format doesn't guarantee alignment
    // Big-endian to match DataOutputStream which writes big-endian
    private static final ValueLayout.OfInt INT_UNALIGNED = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_UNALIGNED = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE_UNALIGNED = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final MemorySegment segment;
    private final Arena arena;
    private final FileFooter footer;

    public MappedFileReader(Path path) throws IOException {
        this.arena = Arena.ofShared(); // shared = accessible from any thread
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            this.segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
        this.footer = readFooter();
    }

    public FileFooter getFooter() {
        return footer;
    }

    /**
     * Read a column's data directly from mapped memory — no file I/O calls.
     * The OS page cache handles everything.
     */
    public ColumnData readColumn(RowGroupMetadata rg, int columnIndex, DataType type) {
        ColumnChunkMetadata chunkMeta = rg.columns().get(columnIndex);
        long offset = chunkMeta.fileOffSet();
        int totalValues = (int) chunkMeta.numValues();
        Object[] values = new Object[totalValues];
        int valueIndex = 0;

        long pos = offset;
        long endPos = offset + chunkMeta.totalSize();

        while (valueIndex < totalValues && pos < endPos) {
            // Read bitmap
            int bitmapSize = segment.get(INT_UNALIGNED, pos);
            pos += 4;
            byte[] bitmapBytes = new byte[bitmapSize];
            MemorySegment.copy(segment, pos, MemorySegment.ofArray(bitmapBytes), 0, bitmapSize);
            pos += bitmapSize;

            if (type == DataType.STRING) {
                // String page
                int numStrings = segment.get(INT_UNALIGNED, pos);
                pos += 4;
                int[] offsets = new int[numStrings + 1];
                for (int i = 0; i <= numStrings; i++) {
                    offsets[i] = segment.get(INT_UNALIGNED, pos);
                    pos += 4;
                }
                int stringDataSize = segment.get(INT_UNALIGNED, pos);
                pos += 4;
                byte[] stringData = new byte[stringDataSize];
                MemorySegment.copy(segment, pos, MemorySegment.ofArray(stringData), 0, stringDataSize);
                pos += stringDataSize;

                int stringIndex = 0;
                int remaining = totalValues - valueIndex;
                int pageTotal = Math.min(bitmapSize * 8, remaining);
                for (int i = 0; i < pageTotal && valueIndex < totalValues; i++) {
                    boolean isNull = (bitmapBytes[i / 8] & (1 << (i % 8))) == 0;
                    if (isNull) {
                        values[valueIndex] = null;
                    } else {
                        int start = offsets[stringIndex];
                        int end = offsets[stringIndex + 1];
                        values[valueIndex] = new String(stringData, start, end - start);
                        stringIndex++;
                    }
                    valueIndex++;
                }
            } else {
                // Fixed-width page
                int remaining = totalValues - valueIndex;
                int pageTotal = Math.min(bitmapSize * 8, remaining);

                for (int i = 0; i < pageTotal && valueIndex < totalValues; i++) {
                    boolean isNull = (bitmapBytes[i / 8] & (1 << (i % 8))) == 0;
                    if (isNull) {
                        values[valueIndex] = null;
                    } else {
                        switch (type) {
                            case INT32 -> { values[valueIndex] = segment.get(INT_UNALIGNED, pos); pos += 4; }
                            case INT64 -> { values[valueIndex] = segment.get(LONG_UNALIGNED, pos); pos += 8; }
                            case FLOAT64 -> { values[valueIndex] = segment.get(DOUBLE_UNALIGNED, pos); pos += 8; }
                        }
                    }
                    valueIndex++;
                }
            }
        }

        return new ColumnData(values, null, totalValues);
    }

    /**
     * Read column directly into a typed primitive array — zero boxing.
     * This is the fastest path for vectorized execution.
     */
    public long[] readLongColumnDirect(RowGroupMetadata rg, int columnIndex) {
        ColumnChunkMetadata chunkMeta = rg.columns().get(columnIndex);
        long offset = chunkMeta.fileOffSet();
        int totalValues = (int) chunkMeta.numValues();
        long[] result = new long[totalValues];
        int valueIndex = 0;

        long pos = offset;
        long endPos = offset + chunkMeta.totalSize();

        while (valueIndex < totalValues && pos < endPos) {
            int bitmapSize = segment.get(INT_UNALIGNED, pos);
            pos += 4 + bitmapSize; // skip bitmap (assume no nulls for speed path)

            int remaining = totalValues - valueIndex;
            int pageTotal = Math.min(bitmapSize * 8, remaining);

            // TIGHT LOOP — directly reading from mapped memory into primitive array
            for (int i = 0; i < pageTotal && valueIndex < totalValues; i++) {
                result[valueIndex] = segment.get(LONG_UNALIGNED, pos);
                pos += 8;
                valueIndex++;
            }
        }

        return result;
    }

    public int[] readIntColumnDirect(RowGroupMetadata rg, int columnIndex) {
        ColumnChunkMetadata chunkMeta = rg.columns().get(columnIndex);
        long offset = chunkMeta.fileOffSet();
        int totalValues = (int) chunkMeta.numValues();
        int[] result = new int[totalValues];
        int valueIndex = 0;

        long pos = offset;
        long endPos = offset + chunkMeta.totalSize();

        while (valueIndex < totalValues && pos < endPos) {
            int bitmapSize = segment.get(INT_UNALIGNED, pos);
            pos += 4 + bitmapSize;

            int remaining = totalValues - valueIndex;
            int pageTotal = Math.min(bitmapSize * 8, remaining);

            for (int i = 0; i < pageTotal && valueIndex < totalValues; i++) {
                result[valueIndex] = segment.get(INT_UNALIGNED, pos);
                pos += 4;
                valueIndex++;
            }
        }

        return result;
    }

    public void close() {
        arena.close();
    }

    private FileFooter readFooter() {
        long fileSize = segment.byteSize();
        // Last 8 bytes: [footer_length (4)][magic (4)]
        int footerLength = segment.get(INT_UNALIGNED, fileSize - 8);
        // Read footer bytes
        long footerStart = fileSize - 8 - footerLength;
        byte[] footerBytes = new byte[footerLength];
        MemorySegment.copy(segment, footerStart, MemorySegment.ofArray(footerBytes), 0, footerLength);

        try {
            return new com.vksql.storage.writer.FileFooterDeSerializer(footerBytes).deSerialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize footer", e);
        }
    }
}
