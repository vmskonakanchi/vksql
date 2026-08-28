package com.vksql.parser.expr;

import java.util.List;

/**
 * Base interface for all expressions in a query plan.
 */
public sealed interface Expr permits
        ColumnRef, IntLiteral, DecimalLiteral, StringLiteral,
        ComparisonExpr, ArithmeticExpr, FunctionCall, AndExpr, OrExpr, NotExpr {
}
