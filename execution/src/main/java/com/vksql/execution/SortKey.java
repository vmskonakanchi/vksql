package com.vksql.execution;

/**
 * Defines a sort key: which column to sort by and the direction.
 */
public record SortKey(int columnIndex, boolean ascending) {
}
