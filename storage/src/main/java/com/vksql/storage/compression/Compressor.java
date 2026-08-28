package com.vksql.storage.compression;

/**
 * Interface for compression/decompression of raw byte arrays.
 */
public interface Compressor {

    /**
     * Compress the given input bytes.
     *
     * @param data the uncompressed data
     * @return the compressed data
     */
    byte[] compress(byte[] data);

    /**
     * Decompress the given compressed bytes.
     *
     * @param data the compressed data
     * @param originalSize the original uncompressed size in bytes
     * @return the decompressed data
     */
    byte[] decompress(byte[] data, int originalSize);

    /**
     * Supported compression types.
     */
    enum CompressionType {
        NONE,
        SNAPPY,
        ZSTD
    }
}
