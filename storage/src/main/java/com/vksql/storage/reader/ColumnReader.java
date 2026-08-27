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
        raf.seek(cMetadata.fileOffSet()); // seek to the column chunk start
        byte[] raw = new byte[(int) cMetadata.totalSize()];
        raf.readFully(raw); // column chunk bytes data
        ByteBuffer buf = ByteBuffer.wrap(raw);

        // deserialize the actual data and read the bitmap first
        int bitMapSize = buf.getInt();
        byte[] bitmapBytes = new byte[bitMapSize];
        buf.get(bitmapBytes);

        int totalValues = (int) cMetadata.numValues();
        Object[] values = new Object[totalValues];

        if (dType == DataType.STRING) {
            // String page layout after bitmap:
            // [numStrings][offset_0][offset_1]...[offset_n][end_offset][string bytes]
            int numStrings = buf.getInt();
            int[] offsets = new int[numStrings + 1];
            for (int i = 0; i <= numStrings; i++) {
                offsets[i] = buf.getInt();
            }
            // remaining string bytes
            byte[] stringData = new byte[offsets[numStrings]];
            buf.get(stringData);

            // reconstruct with nulls
            int stringIndex = 0;
            for (int i = 0; i < totalValues; i++) {
                boolean isNull = (bitmapBytes[i / 8] & (1 << (i % 8))) == 0;
                if (isNull) {
                    values[i] = null;
                } else {
                    int start = offsets[stringIndex];
                    int end = offsets[stringIndex + 1];
                    values[i] = new String(stringData, start, end - start);
                    stringIndex++;
                }
            }
        } else {
            // Fixed-width types: INT32, INT64, FLOAT64
            for (int i = 0; i < totalValues; i++) {
                boolean isNull = (bitmapBytes[i / 8] & (1 << (i % 8))) == 0;
                if (isNull) {
                    values[i] = null;
                } else {
                    switch (dType) {
                        case INT32 -> values[i] = buf.getInt();
                        case INT64 -> values[i] = buf.getLong();
                        case FLOAT64 -> values[i] = buf.getDouble();
                    }
                }
            }
        }

        return new ColumnData(values, null, totalValues);
    }
}
