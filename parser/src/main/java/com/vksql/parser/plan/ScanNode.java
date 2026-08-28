package com.vksql.parser.plan;

/**
 * Read all rows from a table.
 * This is always a leaf node (no child).
 */
public record ScanNode(String tableName) implements RelNode {
}
