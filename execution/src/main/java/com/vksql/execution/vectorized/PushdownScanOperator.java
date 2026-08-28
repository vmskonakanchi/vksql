package com.vksql.execution.vectorized;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.MappedFileReader;
import com.vksql.storage.reader.RowGroupFilter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scan operator with predicate pushdown.
 * Uses min/max stats to skip entire row groups that can't match the filter.
 *
 * Example: 10 row groups, WHERE price > 950
 * If only 1 row group has max(price) > 950, we skip 9 out of 10 — 90% less I/O.
 */
public class PushdownScanOperator implements VectorizedOperator {
    private static final int BATCH_SIZE = 1024;

    private final Path filePath;
    private final Schema schema;
    private final String filterColumn;
    private final String filterOp;
    private final long filterValue;

    private MappedFileReader reader;
    private List<RowGroupMetadata> matchingRowGroups;
    private int currentRgIndex;
    private Object[] currentColumns;
    private int currentRowInRg;
    private int currentRgRows;

    // Stats
    private int totalRowGroups;
    private int skippedRowGroups;

    public PushdownScanOperator(Path filePath, Schema schema,
                                 String filterColumn, String filterOp, long filterValue) {
        this.filePath = filePath;
        this.schema = schema;
        this.filterColumn = filterColumn;
        this.filterOp = filterOp;
        this.filterValue = filterValue;
    }

    @Override
    public void open() {
        try {
            reader = new MappedFileReader(filePath);
            FileFooter footer = reader.getFooter();

            // Filter row groups using min/max stats
            matchingRowGroups = new ArrayList<>();
            totalRowGroups = footer.rows().size();

            for (RowGroupMetadata rg : footer.rows()) {
                if (RowGroupFilter.mightMatch(rg, filterColumn, filterOp, filterValue)) {
                    matchingRowGroups.add(rg);
                } else {
                    skippedRowGroups++;
                }
            }

            currentRgIndex = -1;
            loadNextRowGroup();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public RecordBatch next() {
        while (true) {
            if (currentColumns == null) return null;

            if (currentRowInRg >= currentRgRows) {
                loadNextRowGroup();
                if (currentColumns == null) return null;
            }

            int batchRows = Math.min(BATCH_SIZE, currentRgRows - currentRowInRg);
            Object[] batchColumns = new Object[schema.columnCount()];

            for (int col = 0; col < schema.columnCount(); col++) {
                Object src = currentColumns[col];
                if (src instanceof int[] intSrc) {
                    int[] dst = new int[batchRows];
                    System.arraycopy(intSrc, currentRowInRg, dst, 0, batchRows);
                    batchColumns[col] = dst;
                } else if (src instanceof long[] longSrc) {
                    long[] dst = new long[batchRows];
                    System.arraycopy(longSrc, currentRowInRg, dst, 0, batchRows);
                    batchColumns[col] = dst;
                }
            }

            currentRowInRg += batchRows;
            return new RecordBatch(batchColumns, batchRows);
        }
    }

    @Override
    public void close() {
        if (reader != null) reader.close();
    }

    public int getSkippedRowGroups() { return skippedRowGroups; }
    public int getTotalRowGroups() { return totalRowGroups; }

    private void loadNextRowGroup() {
        currentRgIndex++;
        if (currentRgIndex >= matchingRowGroups.size()) {
            currentColumns = null;
            return;
        }

        RowGroupMetadata rg = matchingRowGroups.get(currentRgIndex);
        currentRgRows = (int) rg.rowCount();
        currentColumns = new Object[schema.columnCount()];

        for (int col = 0; col < schema.columnCount(); col++) {
            DataType type = schema.column(col).type();
            currentColumns[col] = switch (type) {
                case INT32 -> reader.readIntColumnDirect(rg, col);
                case INT64 -> reader.readLongColumnDirect(rg, col);
                default -> null;
            };
        }

        currentRowInRg = 0;
    }
}
