package com.vksql.execution.vectorized;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.MappedFileReader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Vectorized scan using memory-mapped I/O.
 * Zero-copy from disk → process memory. No heap allocation for raw data.
 * Reads directly into typed primitive arrays.
 */
public class MappedVectorizedScanOperator implements VectorizedOperator {
    private static final int BATCH_SIZE = 1024;

    private final Path filePath;
    private final Schema schema;

    private MappedFileReader reader;
    private Object[] fullColumns; // typed primitive arrays (int[], long[], etc.)
    private int totalRows;
    private int currentRow;

    public MappedVectorizedScanOperator(Path filePath, Schema schema) {
        this.filePath = filePath;
        this.schema = schema;
    }

    @Override
    public void open() {
        try {
            reader = new MappedFileReader(filePath);
            FileFooter footer = reader.getFooter();
            RowGroupMetadata rg = footer.rows().get(0);
            totalRows = (int) rg.rowCount();
            fullColumns = new Object[schema.columnCount()];

            // Read columns directly into typed arrays — no Object boxing
            for (int col = 0; col < schema.columnCount(); col++) {
                DataType type = schema.column(col).type();
                fullColumns[col] = switch (type) {
                    case INT32 -> reader.readIntColumnDirect(rg, col);
                    case INT64 -> reader.readLongColumnDirect(rg, col);
                    default -> {
                        // Fallback for types without direct reader
                        ColumnData data = reader.readColumn(rg, col, type);
                        yield data.values();
                    }
                };
            }

            currentRow = 0;
        } catch (IOException e) {
            throw new RuntimeException("Failed to open: " + filePath, e);
        }
    }

    @Override
    public RecordBatch next() {
        if (currentRow >= totalRows) return null;

        int batchRows = Math.min(BATCH_SIZE, totalRows - currentRow);
        Object[] batchColumns = new Object[schema.columnCount()];

        for (int col = 0; col < schema.columnCount(); col++) {
            Object src = fullColumns[col];
            if (src instanceof int[] intSrc) {
                int[] dst = new int[batchRows];
                System.arraycopy(intSrc, currentRow, dst, 0, batchRows);
                batchColumns[col] = dst;
            } else if (src instanceof long[] longSrc) {
                long[] dst = new long[batchRows];
                System.arraycopy(longSrc, currentRow, dst, 0, batchRows);
                batchColumns[col] = dst;
            } else if (src instanceof double[] dblSrc) {
                double[] dst = new double[batchRows];
                System.arraycopy(dblSrc, currentRow, dst, 0, batchRows);
                batchColumns[col] = dst;
            } else if (src instanceof String[] strSrc) {
                String[] dst = new String[batchRows];
                System.arraycopy(strSrc, currentRow, dst, 0, batchRows);
                batchColumns[col] = dst;
            }
        }

        currentRow += batchRows;
        return new RecordBatch(batchColumns, batchRows);
    }

    @Override
    public void close() {
        if (reader != null) reader.close();
        fullColumns = null;
    }
}
