package com.vksql.storage.writer;

import java.util.Arrays;

public class NullBitMap {
    private static final int BIT_SIZE = 2 * 1024; // enough for ~16K values per page
    private byte[] bits;
    private int size; // total positions tracked

    public NullBitMap() {
        bits = new byte[BIT_SIZE];
    }

    public void setNonNull(int position) {
        bits[position / 8] |= (1 << (position % 8));
    }

    public boolean isNull(int position) {
        return (bits[position / 8] & (1 << (position % 8))) == 0;
    }

    public byte[] getBytes() {
        return bits;
    }

    public void trackPosition() {
        size++;
    }

    public int byteSize() {
        return (size + 7) / 8;
    }

    public void reset() {
        Arrays.fill(bits, (byte) 0);
        size = 0;
    }
}
