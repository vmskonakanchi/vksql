package com.vksql.parser.expr;
public record AndExpr(Expr left, Expr right) implements Expr {}
