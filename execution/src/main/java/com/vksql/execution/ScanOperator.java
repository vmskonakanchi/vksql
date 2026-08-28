package com.vksql.execution;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.ColumnReader;
import com.vksql.storage.reader.VksqlFileReader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Reads all rows from a .vkql file.
 * Loads all columns into memory, then returns rows one by one.
 */
public class ScanOperator implements Operator {
    private final Path filePath;
    private final Schema schema;

    private Object[][] columns; // columns[colIndex][rowIndex]
    private int totalRows;
    private int currentRow;

    public ScanOperator(Path filePath, Schema schema) {
        this.filePath = filePath;
        this.schema = schema;
    }

    @Override
    public void open() {
        try {
            var fileReader = new VksqlFileReader(filePath);
            FileFooter footer = fileReader.getFooter();
            RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");

            // For simplicity, read first row group only
            RowGroupMetadata rg = footer.rows().get(0);
            totalRows = (int) rg.rowCount();
            columns = new Object[schema.columnCount()][];

            for (int col = 0; col < schema.columnCount(); col++) {
                ColumnChunkMetadata chunkMeta = rg.columns().get(col);
                DataType type = schema.column(col).type();
                ColumnReader reader = new ColumnReader(raf, chunkMeta, type);
                ColumnData data = reader.read();
                columns[col] = data.values();
            }

            raf.close();
            currentRow = 0;
        } catch (IOException e) {
            throw new RuntimeException("Failed to open file: " + filePath, e);
        }
    }

    @Override
    public Row next() {
        if (currentRow >= totalRows) {
            return null;
        }
        Object[] rowValues = new Object[schema.columnCount()];
        for (int col = 0; col < schema.columnCount(); col++) {
            rowValues[col] = columns[col][currentRow];
        }
        currentRow++;
        return new Row(rowValues);
    }

    @Override
    public void close() {
        columns = null;
    }
}
