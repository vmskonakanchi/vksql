package com.vksql.storage;

import com.vksql.storage.encoding.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for dictionary encoding, run-length encoding, and delta encoding.
 * Verifies correctness (roundtrip) and space savings.
 */
class EncodingTest {

    // ==================== Dictionary Encoding ====================

    @Test
    void dictionaryEncode_lowCardinality() {
        String[] input = {"USA", "UK", "USA", "USA", "UK"};

        DictionaryEncoded encoded = DictionaryEncoder.encode(input);

        // Dictionary should contain unique values in first-occurrence order
        assertArrayEquals(new String[]{"USA", "UK"}, encoded.dictionary());
        // Indices should map back correctly
        assertArrayEquals(new int[]{0, 1, 0, 0, 1}, encoded.indices());
    }

    @Test
    void dictionaryDecode_roundtrip() {
        String[] input = {"USA", "UK", "USA", "USA", "UK"};

        DictionaryEncoded encoded = DictionaryEncoder.encode(input);
        String[] decoded = DictionaryEncoder.decode(encoded.dictionary(), encoded.indices());

        assertArrayEquals(input, decoded);
    }

    @Test
    void dictionaryEncoding_spaceSavings() {
        // 1000 values from 5 distinct countries — high repetition
        String[] countries = {"USA", "UK", "Germany", "France", "Japan"};
        String[] input = new String[1000];
        for (int i = 0; i < input.length; i++) {
            input[i] = countries[i % countries.length];
        }

        DictionaryEncoded encoded = DictionaryEncoder.encode(input);

        // Original: 1000 string references
        // Encoded: 5 dictionary entries + 1000 int indices
        // Space savings: storing 5 strings + 1000 ints vs 1000 strings
        int originalSize = 0;
        for (String s : input) {
            originalSize += s.length() * 2; // 2 bytes per char (UTF-16)
        }

        int encodedSize = 0;
        for (String s : encoded.dictionary()) {
            encodedSize += s.length() * 2;
        }
        encodedSize += encoded.indices().length * 4; // 4 bytes per int index

        assertTrue(encodedSize < originalSize,
            "Encoded size (%d) should be less than original (%d)".formatted(encodedSize, originalSize));
    }

    // ==================== Run-Length Encoding ====================

    @Test
    void rleEncode_repeatedValues() {
        int[] input = {1, 1, 1, 2, 2, 3, 3, 3, 3};

        RleEncoded encoded = RleEncoder.encode(input);

        assertArrayEquals(new int[]{1, 2, 3}, encoded.values());
        assertArrayEquals(new int[]{3, 2, 4}, encoded.runLengths());
    }

    @Test
    void rleDecode_roundtrip() {
        int[] input = {1, 1, 1, 2, 2, 3, 3, 3, 3};

        RleEncoded encoded = RleEncoder.encode(input);
        int[] decoded = RleEncoder.decode(encoded.values(), encoded.runLengths());

        assertArrayEquals(input, decoded);
    }

    @Test
    void rleEncoding_spaceSavings() {
        // 10000 values with long runs (sorted data scenario)
        int[] input = new int[10000];
        for (int i = 0; i < input.length; i++) {
            input[i] = i / 100; // 100 distinct values, each repeated 100 times
        }

        RleEncoded encoded = RleEncoder.encode(input);

        // Original: 10000 ints × 4 bytes = 40000 bytes
        int originalSize = input.length * 4;
        // Encoded: 100 values × 4 bytes + 100 runLengths × 4 bytes = 800 bytes
        int encodedSize = (encoded.values().length + encoded.runLengths().length) * 4;

        assertTrue(encodedSize < originalSize,
            "RLE encoded size (%d) should be much less than original (%d)".formatted(encodedSize, originalSize));
        // Expect ~50x compression for this case
        assertTrue(encodedSize * 40 < originalSize,
            "Expected at least 40x compression for sorted data with 100-length runs");
    }

    @Test
    void rleEncode_emptyInput() {
        RleEncoded encoded = RleEncoder.encode(new int[0]);

        assertEquals(0, encoded.values().length);
        assertEquals(0, encoded.runLengths().length);
    }

    @Test
    void rleEncode_singleElement() {
        int[] input = {42};

        RleEncoded encoded = RleEncoder.encode(input);

        assertArrayEquals(new int[]{42}, encoded.values());
        assertArrayEquals(new int[]{1}, encoded.runLengths());
    }

    // ==================== Delta Encoding ====================

    @Test
    void deltaEncode_monotonicValues() {
        long[] input = {1000, 1001, 1002, 1003, 1005, 1010};

        DeltaEncoded encoded = DeltaEncoder.encode(input);

        assertEquals(1000, encoded.baseValue());
        // Deltas are successive differences: [0, 1, 1, 1, 2, 5]
        assertArrayEquals(new int[]{0, 1, 1, 1, 2, 5}, encoded.deltas());
    }

    @Test
    void deltaDecode_roundtrip() {
        long[] input = {1000, 1001, 1002, 1003, 1005, 1010};

        DeltaEncoded encoded = DeltaEncoder.encode(input);
        long[] decoded = DeltaEncoder.decode(encoded.baseValue(), encoded.deltas());

        assertArrayEquals(input, decoded);
    }

    @Test
    void deltaEncoding_spaceSavings() {
        // Timestamps incrementing by 1ms each — typical time-series data
        long[] input = new long[10000];
        long base = 1_700_000_000_000L; // epoch millis
        for (int i = 0; i < input.length; i++) {
            input[i] = base + i;
        }

        DeltaEncoded encoded = DeltaEncoder.encode(input);

        // Original: 10000 longs × 8 bytes = 80000 bytes
        int originalSize = input.length * 8;
        // Encoded: 1 long (8 bytes) + 10000 ints × 4 bytes = 40008 bytes
        int encodedSize = 8 + encoded.deltas().length * 4;

        assertTrue(encodedSize < originalSize,
            "Delta encoded size (%d) should be less than original (%d)".formatted(encodedSize, originalSize));
        // With int deltas vs long values, expect ~2x compression
        assertTrue(encodedSize * 2 <= originalSize + 16,
            "Expected roughly 2x compression for sequential timestamps");
    }

    @Test
    void deltaEncode_emptyInput() {
        DeltaEncoded encoded = DeltaEncoder.encode(new long[0]);

        assertEquals(0, encoded.baseValue());
        assertEquals(0, encoded.deltas().length);
    }

    @Test
    void deltaEncode_singleElement() {
        long[] input = {42L};

        DeltaEncoded encoded = DeltaEncoder.encode(input);

        assertEquals(42, encoded.baseValue());
        assertArrayEquals(new int[]{0}, encoded.deltas());
    }
}
