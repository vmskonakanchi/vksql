package com.vksql.parser.expr;
public record OrExpr(Expr left, Expr right) implements Expr {}
