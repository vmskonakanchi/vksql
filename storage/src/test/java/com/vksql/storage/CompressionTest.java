package com.vksql.storage;

import com.vksql.storage.compression.*;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for compression roundtrip correctness and compression ratios.
 */
class CompressionTest {

    // --- Roundtrip tests: compress then decompress, verify data matches ---

    @Test
    void noopCompressor_roundtrip() {
        Compressor compressor = new NoopCompressor();
        byte[] original = generateRepetitiveIntData(10_000);

        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed, original.length);

        assertArrayEquals(original, decompressed);
    }

    @Test
    void snappyCompressor_roundtrip() {
        Compressor compressor = new SnappyCompressor();
        byte[] original = generateRepetitiveIntData(10_000);

        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed, original.length);

        assertArrayEquals(original, decompressed);
    }

    @Test
    void zstdCompressor_roundtrip() {
        Compressor compressor = new ZstdCompressor();
        byte[] original = generateRepetitiveIntData(10_000);

        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed, original.length);

        assertArrayEquals(original, decompressed);
    }

    @Test
    void snappyCompressor_roundtrip_randomData() {
        Compressor compressor = new SnappyCompressor();
        byte[] original = generateRandomData(50_000);

        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed, original.length);

        assertArrayEquals(original, decompressed);
    }

    @Test
    void zstdCompressor_roundtrip_randomData() {
        Compressor compressor = new ZstdCompressor();
        byte[] original = generateRandomData(50_000);

        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed, original.length);

        assertArrayEquals(original, decompressed);
    }

    // --- Compression ratio tests ---

    @Test
    void snappyCompressor_achievesAtLeast1_5xRatio() {
        Compressor compressor = new SnappyCompressor();
        byte[] original = generateRepetitiveIntData(100_000);

        byte[] compressed = compressor.compress(original);
        double ratio = (double) original.length / compressed.length;

        assertTrue(ratio >= 1.5,
                "Snappy compression ratio should be at least 1.5x on repetitive int data, but was " + ratio);
    }

    @Test
    void zstdCompressor_achievesAtLeast2xRatio() {
        Compressor compressor = new ZstdCompressor();
        byte[] original = generateRepetitiveIntData(100_000);

        byte[] compressed = compressor.compress(original);
        double ratio = (double) original.length / compressed.length;

        assertTrue(ratio >= 2.0,
                "Zstd compression ratio should be at least 2x on repetitive int data, but was " + ratio);
    }

    @Test
    void noopCompressor_doesNotChangeSize() {
        Compressor compressor = new NoopCompressor();
        byte[] original = generateRepetitiveIntData(1_000);

        byte[] compressed = compressor.compress(original);

        assertEquals(original.length, compressed.length);
        assertArrayEquals(original, compressed);
    }

    // --- Helper methods ---

    /**
     * Generates repetitive int data — simulates a columnar integer column
     * with values cycling through a small range (high compression potential).
     */
    private byte[] generateRepetitiveIntData(int numInts) {
        ByteBuffer buffer = ByteBuffer.allocate(numInts * Integer.BYTES);
        for (int i = 0; i < numInts; i++) {
            buffer.putInt(i % 100); // values cycle 0..99
        }
        return buffer.array();
    }

    /**
     * Generates random byte data (low compression potential).
     */
    private byte[] generateRandomData(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }
}
