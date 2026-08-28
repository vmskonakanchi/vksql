package com.vksql.execution;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.Schema;

/**
 * Filters rows from its input based on a condition.
 * Pulls from child, evaluates condition, only passes matching rows.
 */
public class FilterOperator implements Operator {
    private final Operator input;
    private final Expr condition;
    private final Schema schema;

    public FilterOperator(Operator input, Expr condition, Schema schema) {
        this.input = input;
        this.condition = condition;
        this.schema = schema;
    }

    @Override
    public void open() {
        input.open();
    }

    @Override
    public Row next() {
        while (true) {
            Row row = input.next();
            if (row == null) return null;

            if (evaluate(condition, row)) {
                return row;
            }
            // row didn't match, try next one
        }
    }

    @Override
    public void close() {
        input.close();
    }

    /**
     * Evaluate an expression against a row. Returns true/false for conditions.
     */
    private boolean evaluate(Expr expr, Row row) {
        if (expr instanceof ComparisonExpr cmp) {
            Object leftVal = resolveValue(cmp.left(), row);
            Object rightVal = resolveValue(cmp.right(), row);
            return compare(leftVal, cmp.operator(), rightVal);
        }
        if (expr instanceof AndExpr and) {
            return evaluate(and.left(), row) && evaluate(and.right(), row);
        }
        if (expr instanceof OrExpr or) {
            return evaluate(or.left(), row) || evaluate(or.right(), row);
        }
        if (expr instanceof NotExpr not) {
            return !evaluate(not.input(), row);
        }
        return false;
    }

    /**
     * Get the actual value from an expression (column lookup or literal).
     */
    private Object resolveValue(Expr expr, Row row) {
        if (expr instanceof ColumnRef col) {
            int index = findColumnIndex(col.name());
            return row.get(index);
        }
        if (expr instanceof IntLiteral lit) {
            return lit.value();
        }
        if (expr instanceof DecimalLiteral lit) {
            return lit.value();
        }
        if (expr instanceof StringLiteral lit) {
            return lit.value();
        }
        return null;
    }

    private int findColumnIndex(String name) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.column(i).name().equals(name)) {
                return i;
            }
        }
        throw new RuntimeException("Column not found: " + name);
    }

    @SuppressWarnings("unchecked")
    private boolean compare(Object left, String op, Object right) {
        if (left == null || right == null) return false;

        // Convert to comparable numbers
        double l = toDouble(left);
        double r = toDouble(right);

        return switch (op) {
            case ">" -> l > r;
            case "<" -> l < r;
            case ">=" -> l >= r;
            case "<=" -> l <= r;
            case "=" -> l == r;
            case "!=", "<>" -> l != r;
            default -> false;
        };
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) return Double.parseDouble(s);
        return 0;
    }
}
