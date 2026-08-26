package com.vksql.storage.writer;

import java.util.ArrayList;
import java.util.List;

import com.vksql.storage.format.ColumnChunkResult;
import com.vksql.storage.format.ColumnDescriptor;
import com.vksql.storage.format.Schema;

public class RowGroupWriter {
    private List<ColumnChunkWriter> chunkWriters;
    private Schema schema;
    private static final int MAX_ROW_LIMIT = 1_000_000; // 1M
    private int currentCount; // current count to track rows

    public RowGroupWriter(Schema schema) {
        this.schema = schema;
        chunkWriters = new ArrayList<>(schema.columnCount());
        for (ColumnDescriptor col : schema.columns()) {
            chunkWriters.add(new ColumnChunkWriter(col));
        }
    }

    public void writeRow(Object... values) {
        for (int i = 0; i < values.length; i++) {
            switch (schema.column(i).type()) {
                case INT32 -> chunkWriters.get(i).writeInt32((int) values[i]);
                case INT64 -> chunkWriters.get(i).writeInt64((long) values[i]);
                case FLOAT64 -> chunkWriters.get(i).writeFloat64((double) values[i]);
            }
        }
        currentCount++;
    }

    public boolean isFull() {
        return currentCount >= MAX_ROW_LIMIT;
    }

    public List<ColumnChunkResult> finish() {
        List<ColumnChunkResult> result = new ArrayList<>();

        for (ColumnChunkWriter ccw : chunkWriters) {
            result.add(ccw.finish());
        }

        return result;
    }

    public int getCurrentCount() {
        return this.currentCount;
    }
}
