package com.vksql.network;

import com.vksql.network.coordinator.DataPartitioner;
import com.vksql.storage.format.*;
import com.vksql.storage.reader.ColumnReader;
import com.vksql.storage.reader.VksqlFileReader;
import com.vksql.storage.writer.VksqlFileWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataPartitionerTest {

    @TempDir
    Path tempDir;

    @Test
    void partitionsDataEvenly() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("price", DataType.INT64, 1),
            new ColumnDescriptor("nation", DataType.INT32, 2)
        ));

        // Write source file with 1000 rows
        Path sourceFile = tempDir.resolve("orders.vkql");
        try (VksqlFileWriter writer = new VksqlFileWriter(sourceFile, schema)) {
            for (int i = 0; i < 1000; i++) {
                writer.writeRow(i, (long) (i * 100), i % 25);
            }
        }

        // Partition into 4 partitions by 'id' column (index 0)
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        int numPartitions = 4;
        DataPartitioner.partition(sourceFile, outputDir, schema, 0, numPartitions);

        // Read each partition and count rows
        int totalRows = 0;
        for (int p = 0; p < numPartitions; p++) {
            Path partitionFile = outputDir.resolve("partition_" + p).resolve("orders.vkql");
            assertTrue(Files.exists(partitionFile), "Partition file " + p + " should exist");

            VksqlFileReader reader = new VksqlFileReader(partitionFile);
            FileFooter footer = reader.getFooter();

            int partitionRowCount = 0;
            for (RowGroupMetadata rg : footer.rows()) {
                partitionRowCount += (int) rg.rowCount();
            }

            // Each partition should have approximately 250 rows (1000/4)
            // Allow a tolerance of ±50 since hash distribution isn't perfectly uniform
            assertTrue(partitionRowCount > 200 && partitionRowCount < 300,
                "Partition " + p + " has " + partitionRowCount + " rows, expected ~250");

            totalRows += partitionRowCount;
        }

        // Total rows across all partitions must equal original
        assertEquals(1000, totalRows, "Total rows across all partitions should be 1000");
    }

    @Test
    void partitionedDataIsReadable() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("price", DataType.INT64, 1),
            new ColumnDescriptor("nation", DataType.INT32, 2)
        ));

        // Write source file with 1000 rows
        Path sourceFile = tempDir.resolve("orders.vkql");
        try (VksqlFileWriter writer = new VksqlFileWriter(sourceFile, schema)) {
            for (int i = 0; i < 1000; i++) {
                writer.writeRow(i, (long) (i * 100), i % 25);
            }
        }

        // Partition into 4 partitions by 'id' column (index 0)
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        int numPartitions = 4;
        DataPartitioner.partition(sourceFile, outputDir, schema, 0, numPartitions);

        // Verify data in each partition is actually readable and has correct partition assignment
        for (int p = 0; p < numPartitions; p++) {
            Path partitionFile = outputDir.resolve("partition_" + p).resolve("orders.vkql");
            VksqlFileReader reader = new VksqlFileReader(partitionFile);
            FileFooter footer = reader.getFooter();

            try (RandomAccessFile raf = new RandomAccessFile(partitionFile.toFile(), "r")) {
                for (RowGroupMetadata rg : footer.rows()) {
                    ColumnReader idReader = new ColumnReader(raf, rg.columns().get(0), DataType.INT32);
                    ColumnData idData = idReader.read();

                    // Every id in this partition should hash to partition p
                    for (int i = 0; i < idData.valueCount(); i++) {
                        int id = (int) idData.values()[i];
                        assertEquals(p, DataPartitioner.partitionFor(id, numPartitions),
                            "Row with id=" + id + " should be in partition " + p);
                    }
                }
            }
        }
    }
}
