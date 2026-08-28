package com.vksql.storage.encoding;

/**
 * Result of Run-Length Encoding an int column.
 * Each (value, runLength) pair represents consecutive occurrences.
 */
public record RleEncoded(int[] values, int[] runLengths) {}
