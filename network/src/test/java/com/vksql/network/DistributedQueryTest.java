package com.vksql.network;

import com.vksql.network.coordinator.Coordinator;
import com.vksql.network.coordinator.DataPartitioner;
import com.vksql.network.coordinator.TaskAssignment;
import com.vksql.network.worker.Worker;
import com.vksql.storage.format.*;
import com.vksql.storage.writer.VksqlFileWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for distributed query execution.
 *
 * 1. Creates a source file with 10000 rows
 * 2. Partitions it into 4 partitions using DataPartitioner
 * 3. Creates 4 Worker instances, each pointing to one partition directory
 * 4. Coordinator distributes the query to all 4 workers
 * 5. Merges results and verifies correctness (same result as running on single node)
 *
 * Query: filter price > 250, group by nation, sum(price)
 */
class DistributedQueryTest {

    @TempDir
    Path tempDir;

    /**
     * Schema: (id INT32, price INT64, nation INT32)
     */
    private Schema createSchema() {
        return new Schema(List.of(
                new ColumnDescriptor("id", DataType.INT32, 0),
                new ColumnDescriptor("price", DataType.INT64, 1),
                new ColumnDescriptor("nation", DataType.INT32, 2)
        ));
    }

    @Test
    void distributedQueryMatchesSingleNode() throws Exception {
        Schema schema = createSchema();
        int numRows = 10_000;
        int numNations = 25;
        int numPartitions = 4;
        Random random = new Random(42); // deterministic seed

        // === Step 1: Create source file with 10000 rows ===
        Path sourceFile = tempDir.resolve("orders.vkql");
        try (VksqlFileWriter writer = new VksqlFileWriter(sourceFile, schema)) {
            for (int i = 0; i < numRows; i++) {
                int id = i;
                long price = random.nextLong(1, 500); // prices from 1 to 499
                int nation = i % numNations;          // 25 nations (0-24)
                writer.writeRow(id, price, nation);
            }
        }

        // === Step 2: Compute expected result (single-node execution) ===
        // Use a single Worker on the full source data to compute expected result
        Worker singleNodeWorker = new Worker("single", tempDir, 9999, "localhost:8080");
        Map<Object, Long> expectedResult = singleNodeWorker.executeTask(
                "orders", "price", ">", 250L, 2, 1, "sum"
        );

        // Sanity check: we expect results for most nations since price range is 1-499
        assertFalse(expectedResult.isEmpty(), "Expected result should not be empty");

        // === Step 3: Partition data into 4 partitions ===
        Path partitionsDir = tempDir.resolve("partitions");
        Files.createDirectories(partitionsDir);
        DataPartitioner.partition(sourceFile, partitionsDir, schema, 0, numPartitions);

        // === Step 4: Create 4 Worker instances ===
        Map<String, Worker> workers = new HashMap<>();
        for (int i = 0; i < numPartitions; i++) {
            String workerId = "worker-" + i;
            Path partitionDir = partitionsDir.resolve("partition_" + i);
            workers.put(workerId, new Worker(workerId, partitionDir, 9090 + i, "localhost:8080"));
        }

        // === Step 5: Set up Coordinator and register workers ===
        Coordinator coordinator = new Coordinator(8080);
        for (String workerId : workers.keySet()) {
            coordinator.getWorkerRegistry().register(workerId, "localhost", 9090);
        }

        // === Step 6: Plan and execute the distributed query ===
        String sql = "SELECT sum(price) FROM orders WHERE price > 250 GROUP BY nation";
        List<TaskAssignment> tasks = coordinator.planDistributed(sql, schema);

        // Verify we got one task per worker
        assertEquals(numPartitions, tasks.size(), "Should have one task per worker");

        // Execute the distributed query
        Map<Object, Long> distributedResult = coordinator.executeDistributed(tasks, workers);

        // === Step 7: Verify distributed result matches single-node result ===
        assertEquals(expectedResult.size(), distributedResult.size(),
                "Distributed result should have same number of groups as single-node");

        for (Map.Entry<Object, Long> entry : expectedResult.entrySet()) {
            Object key = entry.getKey();
            Long expectedValue = entry.getValue();
            Long distributedValue = distributedResult.get(key);

            assertNotNull(distributedValue,
                    "Distributed result missing group key: " + key);
            assertEquals(expectedValue, distributedValue,
                    "Mismatch for nation=" + key + ": expected=" + expectedValue
                            + ", distributed=" + distributedValue);
        }
    }

    @Test
    void executeQueryConvenienceMethod() throws Exception {
        Schema schema = createSchema();
        int numRows = 10_000;
        int numNations = 25;
        int numPartitions = 4;
        Random random = new Random(42);

        // Create source file
        Path sourceFile = tempDir.resolve("orders.vkql");
        try (VksqlFileWriter writer = new VksqlFileWriter(sourceFile, schema)) {
            for (int i = 0; i < numRows; i++) {
                writer.writeRow(i, random.nextLong(1, 500), i % numNations);
            }
        }

        // Get expected result from single node
        Worker singleNodeWorker = new Worker("single", tempDir, 9999, "localhost:8080");
        Map<Object, Long> expectedResult = singleNodeWorker.executeTask(
                "orders", "price", ">", 250L, 2, 1, "sum"
        );

        // Partition data
        Path partitionsDir = tempDir.resolve("partitions");
        Files.createDirectories(partitionsDir);
        DataPartitioner.partition(sourceFile, partitionsDir, schema, 0, numPartitions);

        // Create workers and coordinator
        Map<String, Worker> workers = new HashMap<>();
        Coordinator coordinator = new Coordinator(8080);
        for (int i = 0; i < numPartitions; i++) {
            String workerId = "worker-" + i;
            Path partitionDir = partitionsDir.resolve("partition_" + i);
            workers.put(workerId, new Worker(workerId, partitionDir, 9090 + i, "localhost:8080"));
            coordinator.getWorkerRegistry().register(workerId, "localhost", 9090 + i);
        }

        // Use convenience method
        String sql = "SELECT sum(price) FROM orders WHERE price > 250 GROUP BY nation";
        Map<Object, Long> result = coordinator.executeQuery(sql, schema, workers);

        // Verify correctness
        assertEquals(expectedResult.size(), result.size());
        for (Map.Entry<Object, Long> entry : expectedResult.entrySet()) {
            assertEquals(entry.getValue(), result.get(entry.getKey()),
                    "Mismatch for nation=" + entry.getKey());
        }
    }

    @Test
    void planDistributed_noWorkers_throws() {
        Coordinator coordinator = new Coordinator(8080);
        Schema schema = createSchema();

        assertThrows(IllegalStateException.class, () ->
                coordinator.planDistributed("SELECT sum(price) FROM orders WHERE price > 250 GROUP BY nation", schema)
        );
    }
}
