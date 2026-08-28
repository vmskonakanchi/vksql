package com.vksql.parser.plan;

import com.vksql.parser.expr.Expr;
import java.util.List;

/**
 * Keep only these columns/expressions.
 * Example: SELECT price, name
 */
public record ProjectNode(List<Expr> columns, RelNode input) implements RelNode {
}
