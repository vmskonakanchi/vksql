package com.vksql.parser.plan;

import com.vksql.parser.expr.Expr;
import java.util.List;

/**
 * Group rows and compute aggregates.
 * Example: SELECT sum(price) GROUP BY nation
 */
public record AggregateNode(List<Expr> groupBy, List<Expr> aggregates, RelNode input) implements RelNode {
}
