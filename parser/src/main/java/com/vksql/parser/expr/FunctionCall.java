package com.vksql.parser.expr;
import java.util.List;
public record FunctionCall(String name, List<Expr> args) implements Expr {}
