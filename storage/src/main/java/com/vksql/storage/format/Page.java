package com.vksql.storage.format;

public record Page(
        int numValues, // how many values in this page
        int uncompressedSize, // size before compression
        byte[] data // the raw bytes (for now, uncompressed)
) {
}