package com.vksql.execution;

/**
 * Returns only the first N rows from input.
 */
public class LimitOperator implements Operator {
    private final Operator input;
    private final int limit;
    private int count;

    public LimitOperator(Operator input, int limit) {
        this.input = input;
        this.limit = limit;
    }

    @Override
    public void open() {
        input.open();
        count = 0;
    }

    @Override
    public Row next() {
        if (count >= limit) return null;
        Row row = input.next();
        if (row == null) return null;
        count++;
        return row;
    }

    @Override
    public void close() {
        input.close();
    }
}
