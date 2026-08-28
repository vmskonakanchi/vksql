package com.vksql.parser.expr;
public record ComparisonExpr(Expr left, String operator, Expr right) implements Expr {}
