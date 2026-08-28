package com.vksql.execution.vectorized;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.Schema;

/**
 * Vectorized filter: evaluates condition on entire arrays at once.
 * Instead of per-row virtual dispatch, runs a tight loop.
 *
 * The key: for (i=0; i<batchSize; i++) selected[i] = prices[i] > 100
 * CPU auto-vectorizes this with SIMD.
 */
public class VectorizedFilterOperator implements VectorizedOperator {
    private final VectorizedOperator input;
    private final ComparisonExpr condition; // simplified: only handle comparisons for now
    private final Schema schema;

    public VectorizedFilterOperator(VectorizedOperator input, ComparisonExpr condition, Schema schema) {
        this.input = input;
        this.condition = condition;
        this.schema = schema;
    }

    @Override
    public void open() {
        input.open();
    }

    @Override
    public RecordBatch next() {
        while (true) {
            RecordBatch batch = input.next();
            if (batch == null) return null;

            // Evaluate filter on the batch — returns selection of valid row indices
            int[] selected = evaluateFilter(batch);
            if (selected.length == 0) continue; // no rows matched, try next batch

            // Build output batch with only selected rows
            return applySelection(batch, selected);
        }
    }

    @Override
    public void close() {
        input.close();
    }

    /**
     * THE HOT LOOP — this is what makes vectorized fast.
     * No virtual dispatch, no Object boxing, just array access.
     */
    private int[] evaluateFilter(RecordBatch batch) {
        // Find which column we're filtering on
        String colName = ((ColumnRef) condition.left()).name();
        int colIndex = findColumnIndex(colName);
        long compareValue = ((IntLiteral) condition.right()).value();
        String op = condition.operator();

        int rowCount = batch.rowCount();
        int[] tempSelected = new int[rowCount];
        int selectedCount = 0;

        // Tight loop — JIT will auto-vectorize this
        Object col = batch.getColumn(colIndex);
        if (col instanceof long[] longCol) {
            for (int i = 0; i < rowCount; i++) {
                if (compareLong(longCol[i], op, compareValue)) {
                    tempSelected[selectedCount++] = i;
                }
            }
        } else if (col instanceof int[] intCol) {
            for (int i = 0; i < rowCount; i++) {
                if (compareLong(intCol[i], op, compareValue)) {
                    tempSelected[selectedCount++] = i;
                }
            }
        }

        // Trim to actual size
        int[] result = new int[selectedCount];
        System.arraycopy(tempSelected, 0, result, 0, selectedCount);
        return result;
    }

    private RecordBatch applySelection(RecordBatch batch, int[] selected) {
        Object[] newColumns = new Object[batch.columnCount()];

        for (int col = 0; col < batch.columnCount(); col++) {
            Object srcCol = batch.getColumn(col);
            if (srcCol instanceof int[] src) {
                int[] dst = new int[selected.length];
                for (int i = 0; i < selected.length; i++) dst[i] = src[selected[i]];
                newColumns[col] = dst;
            } else if (srcCol instanceof long[] src) {
                long[] dst = new long[selected.length];
                for (int i = 0; i < selected.length; i++) dst[i] = src[selected[i]];
                newColumns[col] = dst;
            } else if (srcCol instanceof double[] src) {
                double[] dst = new double[selected.length];
                for (int i = 0; i < selected.length; i++) dst[i] = src[selected[i]];
                newColumns[col] = dst;
            } else if (srcCol instanceof String[] src) {
                String[] dst = new String[selected.length];
                for (int i = 0; i < selected.length; i++) dst[i] = src[selected[i]];
                newColumns[col] = dst;
            }
        }

        return new RecordBatch(newColumns, selected.length);
    }

    private boolean compareLong(long value, String op, long compareValue) {
        return switch (op) {
            case ">" -> value > compareValue;
            case "<" -> value < compareValue;
            case ">=" -> value >= compareValue;
            case "<=" -> value <= compareValue;
            case "=" -> value == compareValue;
            case "!=", "<>" -> value != compareValue;
            default -> false;
        };
    }

    private int findColumnIndex(String name) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.column(i).name().equals(name)) return i;
        }
        throw new RuntimeException("Column not found: " + name);
    }
}
