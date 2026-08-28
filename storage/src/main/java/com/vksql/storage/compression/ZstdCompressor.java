package com.vksql.storage.compression;

import com.github.luben.zstd.Zstd;

/**
 * Compressor implementation using Zstandard (Zstd) compression.
 * Zstd provides excellent compression ratios with good speed.
 */
public class ZstdCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] data) {
        return Zstd.compress(data);
    }

    @Override
    public byte[] decompress(byte[] data, int originalSize) {
        return Zstd.decompress(data, originalSize);
    }
}
