package com.vksql.parser.plan;

import com.vksql.parser.expr.Expr;

/**
 * Combine two tables on a condition.
 * Example: orders JOIN customers ON orders.cust_id = customers.id
 */
public record JoinNode(RelNode left, RelNode right, Expr condition) implements RelNode {
}
