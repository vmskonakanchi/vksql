package com.vksql.parser.expr;
public record ArithmeticExpr(Expr left, String operator, Expr right) implements Expr {}
