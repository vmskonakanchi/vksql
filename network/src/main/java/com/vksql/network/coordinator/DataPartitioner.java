package com.vksql.network.coordinator;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.ColumnReader;
import com.vksql.storage.reader.VksqlFileReader;
import com.vksql.storage.writer.VksqlFileWriter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a table's data across N worker partitions using hash partitioning.
 *
 * partition_id = hash(partition_key) % num_partitions
 *
 * Each partition is a directory containing .vkql files that belong to one worker.
 * This simulates distributing data across multiple nodes.
 */
public class DataPartitioner {

    /**
     * Split a source file into N partitions by hashing a column.
     *
     * @param sourcePath    original .vkql file
     * @param outputDir     base directory (will create partition_0/, partition_1/, etc.)
     * @param schema        table schema
     * @param partitionCol  column index to hash on
     * @param numPartitions how many partitions (= number of workers)
     */
    public static void partition(Path sourcePath, Path outputDir, Schema schema,
                                  int partitionCol, int numPartitions) throws IOException {
        // Create partition directories
        for (int i = 0; i < numPartitions; i++) {
            Files.createDirectories(outputDir.resolve("partition_" + i));
        }

        // Derive table name from the source file name
        String fileName = sourcePath.getFileName().toString();

        // Read the file footer to get row group metadata
        VksqlFileReader fileReader = new VksqlFileReader(sourcePath);
        FileFooter footer = fileReader.getFooter();

        // Read all columns for each row group and collect rows per partition
        // Each partition gets a list of rows (each row is an Object[])
        @SuppressWarnings("unchecked")
        List<Object[]>[] partitionRows = new List[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            partitionRows[i] = new ArrayList<>();
        }

        try (RandomAccessFile raf = new RandomAccessFile(sourcePath.toFile(), "r")) {
            for (RowGroupMetadata rg : footer.rows()) {
                int numColumns = schema.columnCount();
                int rowCount = (int) rg.rowCount();

                // Read all columns in this row group
                ColumnData[] columnDataArr = new ColumnData[numColumns];
                for (int col = 0; col < numColumns; col++) {
                    ColumnChunkMetadata colMeta = rg.columns().get(col);
                    DataType colType = schema.column(col).type();
                    ColumnReader colReader = new ColumnReader(raf, colMeta, colType);
                    columnDataArr[col] = colReader.read();
                }

                // For each row, determine the partition and collect it
                for (int row = 0; row < rowCount; row++) {
                    Object partitionValue = columnDataArr[partitionCol].values()[row];
                    int partitionId = partitionFor(partitionValue, numPartitions);

                    Object[] rowData = new Object[numColumns];
                    for (int col = 0; col < numColumns; col++) {
                        rowData[col] = columnDataArr[col].values()[row];
                    }
                    partitionRows[partitionId].add(rowData);
                }
            }
        }

        // Write each partition's rows to its output file
        for (int i = 0; i < numPartitions; i++) {
            Path partitionFile = outputDir.resolve("partition_" + i).resolve(fileName);
            try (VksqlFileWriter writer = new VksqlFileWriter(partitionFile, schema)) {
                for (Object[] row : partitionRows[i]) {
                    writer.writeRow(row);
                }
            }
        }
    }

    /**
     * Hash function for partitioning.
     * Uses Math.floorMod to handle negative hashes correctly.
     */
    public static int partitionFor(Object value, int numPartitions) {
        return Math.floorMod(value.hashCode(), numPartitions);
    }
}
