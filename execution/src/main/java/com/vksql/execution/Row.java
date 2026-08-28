package com.vksql.execution;

import java.util.List;

/**
 * A single row of data — just an ordered list of values.
 * values[0] = first column, values[1] = second column, etc.
 */
public record Row(Object[] values) {

    public Object get(int index) {
        return values[index];
    }

    public int columnCount() {
        return values.length;
    }

    @Override
    public String toString() {
        return java.util.Arrays.toString(values);
    }
}
