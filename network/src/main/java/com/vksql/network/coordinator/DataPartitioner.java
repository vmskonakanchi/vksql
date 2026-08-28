package com.vksql.network.coordinator;

import com.vksql.storage.format.*;
import com.vksql.storage.writer.VksqlFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        // TODO:
        // 1. Read all rows from source file
        // 2. For each row: compute hash of partition column → partition_id
        // 3. Write row to the appropriate partition's file
        //
        // Result: outputDir/partition_0/table.vkql
        //         outputDir/partition_1/table.vkql
        //         ...
        //
        // Each worker gets one partition directory.

        for (int i = 0; i < numPartitions; i++) {
            Files.createDirectories(outputDir.resolve("partition_" + i));
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
