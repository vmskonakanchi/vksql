package com.vksql.parser.plan;

import com.vksql.parser.expr.Expr;

/**
 * Keep only rows where condition is true.
 * Example: WHERE price > 100
 */
public record FilterNode(Expr condition, RelNode input) implements RelNode {
}
