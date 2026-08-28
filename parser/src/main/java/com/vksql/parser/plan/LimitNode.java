package com.vksql.parser.plan;

/**
 * Return only the first N rows.
 * Example: LIMIT 10
 */
public record LimitNode(int limit, RelNode input) implements RelNode {
}
