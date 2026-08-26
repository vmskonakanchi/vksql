package com.vksql.storage.writer;

import java.util.ArrayList;
import java.util.List;

import com.vksql.storage.format.*;

public class ColumnChunkWriter {
    private final ColumnDescriptor descriptor;
    private final PageWriter pageWriter;
    private final List<Page> pages = new ArrayList<>();

    // Stats
    private long minValue = Long.MAX_VALUE;
    private long maxValue = Long.MIN_VALUE;
    private int totalValueCount = 0;

    public ColumnChunkWriter(ColumnDescriptor descriptor) {
        this.descriptor = descriptor;
        this.pageWriter = new PageWriter(descriptor.type());
    }

    public void writeInt32(int value) {
        pageWriter.writeInt32(value);
        minValue = Math.min(minValue, value);
        maxValue = Math.max(maxValue, value);
        totalValueCount++;
        if (pageWriter.isFull()) {
            pages.add(pageWriter.flush());
        }
    }

    public void writeInt64(long value) {
        pageWriter.writeInt64(value);
        minValue = Math.min(minValue, value);
        maxValue = Math.max(maxValue, value);
        totalValueCount++;
        if (pageWriter.isFull()) {
            pages.add(pageWriter.flush());
        }
    }

    public void writeFloat64(double value) {
        pageWriter.writeFloat64(value);
        minValue = (long) Math.min(minValue, value);
        maxValue = (long) Math.max(maxValue, value);
        totalValueCount++;
        if (pageWriter.isFull()) {
            pages.add(pageWriter.flush());
        }
    }

    // Call when row group is complete
    public ColumnChunkResult finish() {
        if (pageWriter.hasData()) {
            pages.add(pageWriter.flush()); // flush leftover
        }
        return new ColumnChunkResult(descriptor, pages, minValue, maxValue, totalValueCount);
    }
}
