package com.vksql.storage.encoding;

/**
 * Result of delta encoding a long column.
 * Stores the first value as baseValue and successive differences as deltas.
 */
public record DeltaEncoded(long baseValue, int[] deltas) {}
