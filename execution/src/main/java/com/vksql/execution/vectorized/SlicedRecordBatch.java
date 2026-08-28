package com.vksql.execution.vectorized;

/**
 * A zero-copy batch that references a slice of the full column arrays.
 * Instead of copying data into new arrays per batch, we just track
 * the start offset and length into the original arrays.
 *
 * This avoids allocating 1024-element arrays hundreds of times per query.
 */
public class SlicedRecordBatch {
    private final Object[] fullColumns; // full column arrays (int[], long[], etc.)
    private final int offset;           // start index in the arrays
    private final int length;           // number of valid rows

    public SlicedRecordBatch(Object[] fullColumns, int offset, int length) {
        this.fullColumns = fullColumns;
        this.offset = offset;
        this.length = length;
    }

    public int rowCount() { return length; }
    public int columnCount() { return fullColumns.length; }
    public int offset() { return offset; }

    // Access the full backing array — caller uses offset + length to bound their loops
    public int[] getIntColumn(int index) { return (int[]) fullColumns[index]; }
    public long[] getLongColumn(int index) { return (long[]) fullColumns[index]; }
    public double[] getDoubleColumn(int index) { return (double[]) fullColumns[index]; }
    public String[] getStringColumn(int index) { return (String[]) fullColumns[index]; }
    public Object getColumn(int index) { return fullColumns[index]; }
}
