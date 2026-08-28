package com.vksql.storage.reader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import com.vksql.storage.format.ColumnChunkMetadata;
import com.vksql.storage.format.ColumnData;
import com.vksql.storage.format.DataType;

public class ColumnReader {
    private final RandomAccessFile raf;
    private final ColumnChunkMetadata cMetadata;
    private final DataType dType;

    public ColumnReader(RandomAccessFile raf, ColumnChunkMetadata cMetadata, DataType dType) {
        this.raf = raf;
        this.cMetadata = cMetadata;
        this.dType = dType;
    }

    public ColumnData read() throws IOException {
        raf.seek(cMetadata.fileOffSet());
        byte[] raw = new byte[(int) cMetadata.totalSize()];
        raf.readFully(raw);
        ByteBuffer buf = ByteBuffer.wrap(raw);

        int totalValues = (int) cMetadata.numValues();
        Object[] values = new Object[totalValues];
        int valueIndex = 0;

        // Read page by page until we've read all values
        while (valueIndex < totalValues && buf.hasRemaining()) {
            // Each page starts with: [bitmapSize (4 bytes)][bitmap][data]
            int bitmapSize = buf.getInt();
            byte[] bitmapBytes = new byte[bitmapSize];
            buf.get(bitmapBytes);

            if (dType == DataType.STRING) {
                // String page: [numStrings][offsets...][end_offset][string bytes]
                int numStrings = buf.getInt();
                int[] offsets = new int[numStrings + 1];
                for (int i = 0; i <= numStrings; i++) {
                    offsets[i] = buf.getInt();
                }
                byte[] stringData = new byte[offsets[numStrings]];
                buf.get(stringData);

                // Figure out how many total positions this page covers
                int pageValues = bitmapSize * 8; // max values this bitmap can track
                // But actual count is numStrings (non-null) + nulls
                // We need to count from bitmap: total positions = find actual count
                int pageTotal = countPageValues(bitmapBytes, bitmapSize, totalValues - valueIndex);

                int stringIndex = 0;
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
                // Fixed-width page: values packed after bitmap
                // Count how many values in this page by reading until next page or end
                int pageStart = buf.position();
                int bytesPerValue = switch (dType) {
                    case INT32 -> 4;
                    case INT64 -> 8;
                    case FLOAT64 -> 8;
                    default -> 4;
                };

                // Determine how many values this page has
                // Count non-null bits to know how many data values are stored
                int remaining = totalValues - valueIndex;
                int bitmapCapacity = bitmapSize * 8;
                int pageTotal = Math.min(bitmapCapacity, remaining);

                // Count non-null values to know how much data to expect
                int nonNullCount = 0;
                for (int i = 0; i < pageTotal; i++) {
                    if ((bitmapBytes[i / 8] & (1 << (i % 8))) != 0) {
                        nonNullCount++;
                    }
                }

                // Read values
                for (int i = 0; i < pageTotal && valueIndex < totalValues; i++) {
                    boolean isNull = (bitmapBytes[i / 8] & (1 << (i % 8))) == 0;
                    if (isNull) {
                        values[valueIndex] = null;
                    } else {
                        switch (dType) {
                            case INT32 -> values[valueIndex] = buf.getInt();
                            case INT64 -> values[valueIndex] = buf.getLong();
                            case FLOAT64 -> values[valueIndex] = buf.getDouble();
                        }
                    }
                    valueIndex++;
                }
            }
        }

        return new ColumnData(values, null, totalValues);
    }

    /**
     * Count actual values in a page from bitmap.
     * The page might not use all bits in the last byte.
     */
    private int countPageValues(byte[] bitmap, int bitmapSize, int maxRemaining) {
        return Math.min(bitmapSize * 8, maxRemaining);
    }
}
