package com.vksql.storage.encoding;

/**
 * Result of dictionary encoding a String column.
 * The dictionary maps indices to unique values; indices reference the dictionary.
 */
public record DictionaryEncoded(String[] dictionary, int[] indices) {}
