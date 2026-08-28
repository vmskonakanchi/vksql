package com.vksql.storage.compression;

/**
 * No-op compressor that returns data unchanged.
 * Used when compression is disabled (CompressionType.NONE).
 */
public class NoopCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] data) {
        return data;
    }

    @Override
    public byte[] decompress(byte[] data, int originalSize) {
        return data;
    }
}
