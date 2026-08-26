package com.vksql.storage.writer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.vksql.storage.format.*;

public class PageWriter {
    private final ByteBuffer buffer; // accumulates values
    private int valueCount;
    private static final int TARGET_PAGE_SIZE = 64 * 1024; // 64KB

    public PageWriter(DataType type) {
        this.buffer = ByteBuffer.allocate(TARGET_PAGE_SIZE + 8);
    }

    // Call this for each value
    public void writeInt32(int value) {
        buffer.putInt(value);
        valueCount++;
    }

    public void writeInt64(long value) {
        buffer.putLong(value);
        valueCount++;
    }

    public void writeFloat64(double value) {
        buffer.putDouble(value);
        valueCount++;
    }

    public boolean isFull() {
        return buffer.position() >= TARGET_PAGE_SIZE;
    }

    public boolean hasData() {
        return valueCount > 0;
    }

    // Flush buffer into a Page object, reset
    public Page flush() {
        byte[] data = Arrays.copyOf(buffer.array(), buffer.position());
        Page page = new Page(valueCount, data.length, data);
        buffer.clear();
        valueCount = 0;
        return page;
    }
}
