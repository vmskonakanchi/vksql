package com.vksql.storage.writer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.vksql.storage.format.*;

public class StringPageWriter implements IPageWriter {
    private final ByteBuffer offsetBuffer; // accumulates strings
    private final ByteBuffer stringBuffer; // accumulates strings
    private int valueCount;
    private static final int TARGET_PAGE_SIZE = 64 * 1024; // 64KB
    private final NullBitMap nullBitMap;

    public StringPageWriter(DataType type) {
        this.offsetBuffer = ByteBuffer.allocate(TARGET_PAGE_SIZE + 8);
        this.stringBuffer = ByteBuffer.allocate(TARGET_PAGE_SIZE + 8);
        this.nullBitMap = new NullBitMap();
    }

    public void writeString(String value) {
        // first write the position of the bytes and then
        byte[] bytes = value.getBytes();
        nullBitMap.setNonNull(valueCount);
        nullBitMap.trackPosition();
        offsetBuffer.putInt(stringBuffer.position());
        stringBuffer.put(bytes);
        valueCount++;
    }

    @Override
    public void writeNull() {
        nullBitMap.trackPosition();
        valueCount++;
    }

    public boolean isFull() {
        return (offsetBuffer.position() + stringBuffer.position()) >= TARGET_PAGE_SIZE;
    }

    public boolean hasData() {
        return valueCount > 0;
    }

    public Page flush() {
        int bitmapSize = nullBitMap.byteSize();
        ByteBuffer combined = ByteBuffer.allocate(
                4 + bitmapSize + // bitmap size int + bitmap bytes
                        4 + offsetBuffer.position() + 4 + stringBuffer.position() // string stuff
        );
        // bitmap
        combined.putInt(bitmapSize);
        combined.put(nullBitMap.getBytes(), 0, bitmapSize);
        // string data: numNonNullStrings, offsets, end offset, string bytes
        int nonNullCount = offsetBuffer.position() / 4;
        combined.putInt(nonNullCount);
        combined.put(offsetBuffer.array(), 0, offsetBuffer.position());
        combined.putInt(stringBuffer.position());
        combined.put(stringBuffer.array(), 0, stringBuffer.position());

        byte[] data = Arrays.copyOf(combined.array(), combined.position());
        Page page = new Page(valueCount, data.length, data);
        offsetBuffer.clear();
        stringBuffer.clear();
        nullBitMap.reset();
        valueCount = 0;
        return page;
    }

}
