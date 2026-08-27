package com.vksql.storage.writer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.vksql.storage.format.*;

public class PageWriter implements IPageWriter {
    private final ByteBuffer buffer; // accumulates values
    private int valueCount;
    private static final int TARGET_PAGE_SIZE = 64 * 1024; // 64KB
    private final NullBitMap nullBitMap;

    public PageWriter(DataType type) {
        this.buffer = ByteBuffer.allocate(TARGET_PAGE_SIZE + 8);
        this.nullBitMap = new NullBitMap();
    }

    // Call this for each value
    public void writeInt32(int value) {
        nullBitMap.setNonNull(valueCount);
        nullBitMap.trackPosition();
        buffer.putInt(value);
        valueCount++;
    }

    public void writeInt64(long value) {
        nullBitMap.setNonNull(valueCount);
        nullBitMap.trackPosition();
        buffer.putLong(value);
        valueCount++;
    }

    public void writeFloat64(double value) {
        nullBitMap.setNonNull(valueCount);
        nullBitMap.trackPosition();
        buffer.putDouble(value);
        valueCount++;
    }

    @Override
    public void writeNull() {
        nullBitMap.trackPosition();
        valueCount++;
    }

    public boolean isFull() {
        return buffer.position() >= TARGET_PAGE_SIZE;
    }

    public boolean hasData() {
        return valueCount > 0;
    }

    public Page flush() {
        int bitmapSize = nullBitMap.byteSize();
        byte[] data = new byte[4 + bitmapSize + buffer.position()]; // 4 for bitmap size int
        ByteBuffer combined = ByteBuffer.wrap(data);
        combined.putInt(bitmapSize);
        combined.put(nullBitMap.getBytes(), 0, bitmapSize);
        combined.put(buffer.array(), 0, buffer.position());

        Page page = new Page(valueCount, data.length, data);
        buffer.clear();
        nullBitMap.reset();
        valueCount = 0;
        return page;
    }

}
