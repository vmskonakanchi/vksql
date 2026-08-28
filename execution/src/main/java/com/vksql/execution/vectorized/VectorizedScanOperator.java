package com.vksql.execution.vectorized;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.ColumnReader;
import com.vksql.storage.reader.VksqlFileReader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Reads columns as arrays and returns batches of 1024 rows.
 */
public class VectorizedScanOperator implements VectorizedOperator {
    private static final int BATCH_SIZE = 1024;

    private final Path filePath;
    private final Schema schema;

    private Object[] fullColumns; // full column arrays loaded from disk
    private int totalRows;
    private int currentRow;

    public VectorizedScanOperator(Path filePath, Schema schema) {
        this.filePath = filePath;
        this.schema = schema;
    }

    @Override
    public void open() {
        try {
            var fileReader = new VksqlFileReader(filePath);
            FileFooter footer = fileReader.getFooter();
            RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");

            RowGroupMetadata rg = footer.rows().get(0);
            totalRows = (int) rg.rowCount();
            fullColumns = new Object[schema.columnCount()];

            for (int col = 0; col < schema.columnCount(); col++) {
                ColumnChunkMetadata chunkMeta = rg.columns().get(col);
                DataType type = schema.column(col).type();
                ColumnReader reader = new ColumnReader(raf, chunkMeta, type);
                ColumnData data = reader.read();

                // Convert Object[] to typed arrays for tight loops
                fullColumns[col] = toTypedArray(data.values(), type, totalRows);
            }

            raf.close();
            currentRow = 0;
        } catch (IOException e) {
            throw new RuntimeException("Failed to open file: " + filePath, e);
        }
    }

    @Override
    public RecordBatch next() {
        if (currentRow >= totalRows) return null;

        int batchRows = Math.min(BATCH_SIZE, totalRows - currentRow);
        Object[] batchColumns = new Object[schema.columnCount()];

        for (int col = 0; col < schema.columnCount(); col++) {
            DataType type = schema.column(col).type();
            batchColumns[col] = sliceColumn(fullColumns[col], type, currentRow, batchRows);
        }

        currentRow += batchRows;
        return new RecordBatch(batchColumns, batchRows);
    }

    @Override
    public void close() {
        fullColumns = null;
    }

    private Object toTypedArray(Object[] values, DataType type, int count) {
        return switch (type) {
            case INT32 -> {
                int[] arr = new int[count];
                for (int i = 0; i < count; i++) arr[i] = values[i] != null ? (int) values[i] : 0;
                yield arr;
            }
            case INT64 -> {
                long[] arr = new long[count];
                for (int i = 0; i < count; i++) arr[i] = values[i] != null ? (long) values[i] : 0;
                yield arr;
            }
            case FLOAT64 -> {
                double[] arr = new double[count];
                for (int i = 0; i < count; i++) arr[i] = values[i] != null ? (double) values[i] : 0;
                yield arr;
            }
            case STRING -> {
                String[] arr = new String[count];
                for (int i = 0; i < count; i++) arr[i] = (String) values[i];
                yield arr;
            }
        };
    }

    private Object sliceColumn(Object fullColumn, DataType type, int start, int length) {
        return switch (type) {
            case INT32 -> { int[] src = (int[]) fullColumn; int[] dst = new int[length]; System.arraycopy(src, start, dst, 0, length); yield dst; }
            case INT64 -> { long[] src = (long[]) fullColumn; long[] dst = new long[length]; System.arraycopy(src, start, dst, 0, length); yield dst; }
            case FLOAT64 -> { double[] src = (double[]) fullColumn; double[] dst = new double[length]; System.arraycopy(src, start, dst, 0, length); yield dst; }
            case STRING -> { String[] src = (String[]) fullColumn; String[] dst = new String[length]; System.arraycopy(src, start, dst, 0, length); yield dst; }
        };
    }
}
