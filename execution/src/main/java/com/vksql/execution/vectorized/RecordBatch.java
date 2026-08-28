package com.vksql.execution.vectorized;

/**
 * A batch of rows stored as columnar arrays.
 * Instead of Row[1024] with Object boxing, we have:
 *   int[] column0, long[] column1, etc.
 *
 * This is cache-friendly and JIT-friendly — tight loops over primitive arrays.
 */
public class RecordBatch {
    private final Object[] columns; // each element is int[], long[], double[], or String[]
    private final int rowCount;     // how many rows are valid in this batch

    public RecordBatch(Object[] columns, int rowCount) {
        this.columns = columns;
        this.rowCount = rowCount;
    }

    public int rowCount() { return rowCount; }
    public int columnCount() { return columns.length; }

    public int[] getIntColumn(int index) { return (int[]) columns[index]; }
    public long[] getLongColumn(int index) { return (long[]) columns[index]; }
    public double[] getDoubleColumn(int index) { return (double[]) columns[index]; }
    public String[] getStringColumn(int index) { return (String[]) columns[index]; }
    public Object getColumn(int index) { return columns[index]; }
}
