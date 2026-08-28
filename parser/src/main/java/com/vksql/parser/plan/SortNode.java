package com.vksql.parser.plan;

import com.vksql.parser.expr.Expr;
import java.util.List;

/**
 * Sort rows by given expressions.
 * Example: ORDER BY price DESC
 */
public record SortNode(List<SortKey> keys, RelNode input) implements RelNode {

    public record SortKey(Expr expr, boolean ascending) {}
}
