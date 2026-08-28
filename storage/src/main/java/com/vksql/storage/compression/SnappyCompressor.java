package com.vksql.storage.compression;

import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Compressor implementation using Snappy compression.
 * Snappy prioritizes speed over compression ratio.
 */
public class SnappyCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] data) {
        try {
            return Snappy.compress(data);
        } catch (IOException e) {
            throw new UncheckedIOException("Snappy compression failed", e);
        }
    }

    @Override
    public byte[] decompress(byte[] data, int originalSize) {
        try {
            return Snappy.uncompress(data);
        } catch (IOException e) {
            throw new UncheckedIOException("Snappy decompression failed", e);
        }
    }
}
