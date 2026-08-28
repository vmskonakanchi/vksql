package com.vksql.execution;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.Schema;

import java.util.List;

/**
 * Selects specific columns from the input.
 * Input might have (id, name, price) but we only want (price).
 */
public class ProjectOperator implements Operator {
    private final Operator input;
    private final List<Expr> columns;
    private final Schema inputSchema;

    public ProjectOperator(Operator input, List<Expr> columns, Schema inputSchema) {
        this.input = input;
        this.columns = columns;
        this.inputSchema = inputSchema;
    }

    @Override
    public void open() {
        input.open();
    }

    @Override
    public Row next() {
        Row row = input.next();
        if (row == null) return null;

        Object[] projected = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            projected[i] = resolveValue(columns.get(i), row);
        }
        return new Row(projected);
    }

    @Override
    public void close() {
        input.close();
    }

    private Object resolveValue(Expr expr, Row row) {
        if (expr instanceof ColumnRef col) {
            if (col.name().equals("*")) {
                return row; // SELECT * — handled differently in practice
            }
            int index = findColumnIndex(col.name());
            return row.get(index);
        }
        return null;
    }

    private int findColumnIndex(String name) {
        for (int i = 0; i < inputSchema.columnCount(); i++) {
            if (inputSchema.column(i).name().equals(name)) {
                return i;
            }
        }
        throw new RuntimeException("Column not found: " + name);
    }
}
