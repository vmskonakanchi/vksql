package com.vksql.network;

import com.vksql.network.coordinator.CoordinatorServer;
import com.vksql.network.proto.CoordinatorServiceGrpc;
import com.vksql.network.proto.QueryRequest;
import com.vksql.network.proto.QueryResult;
import com.vksql.network.proto.RecordBatch;
import com.vksql.network.worker.Worker;
import com.vksql.network.worker.WorkerServer;
import com.vksql.storage.format.ColumnDescriptor;
import com.vksql.storage.format.DataType;
import com.vksql.storage.format.Schema;
import com.vksql.storage.writer.VksqlFileWriter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the gRPC wire protocol.
 *
 * Starts 1 coordinator + 2 workers on localhost with different ports.
 * Each worker has a partition of the data. A client sends a query to the
 * coordinator, which distributes it to workers over gRPC. The test verifies
 * the merged result matches what a single-node execution would produce.
 */
class GrpcIntegrationTest {

    @TempDir
    Path tempDir;

    private static final int COORDINATOR_PORT = 50051;
    private static final int WORKER1_PORT = 50052;
    private static final int WORKER2_PORT = 50053;

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
    void distributedGrpcQueryReturnsCorrectMergedResult() throws Exception {
        Schema schema = createSchema();
        int numRows = 1000;
        int numNations = 10;
        Random random = new Random(42);

        // === Step 1: Create partitioned data for 2 workers ===
        Path worker1Dir = tempDir.resolve("worker1");
        Path worker2Dir = tempDir.resolve("worker2");
        Files.createDirectories(worker1Dir);
        Files.createDirectories(worker2Dir);

        // Split data: even IDs go to worker1, odd IDs go to worker2
        // Also write a combined file for expected result computation
        Path combinedDir = tempDir.resolve("combined");
        Files.createDirectories(combinedDir);

        try (VksqlFileWriter writer1 = new VksqlFileWriter(worker1Dir.resolve("orders.vkql"), schema);
             VksqlFileWriter writer2 = new VksqlFileWriter(worker2Dir.resolve("orders.vkql"), schema);
             VksqlFileWriter writerAll = new VksqlFileWriter(combinedDir.resolve("orders.vkql"), schema)) {

            for (int i = 0; i < numRows; i++) {
                int id = i;
                long price = random.nextLong(1, 500);
                int nation = i % numNations;

                writerAll.writeRow(id, price, nation);
                if (i % 2 == 0) {
                    writer1.writeRow(id, price, nation);
                } else {
                    writer2.writeRow(id, price, nation);
                }
            }
        }

        // === Step 2: Compute expected result (single-node) ===
        Worker singleNode = new Worker("single", combinedDir, 9999, "localhost:" + COORDINATOR_PORT);
        Map<Object, Long> expectedResult = singleNode.executeTask(
                "orders", "price", ">", 250L, 2, 1, "sum"
        );
        assertFalse(expectedResult.isEmpty(), "Expected result should not be empty");

        // === Step 3: Start coordinator server ===
        CoordinatorServer coordinatorServer = new CoordinatorServer(COORDINATOR_PORT, schema);
        coordinatorServer.start();

        // === Step 4: Create workers and start worker servers ===
        Worker worker1 = new Worker("worker-1", worker1Dir, WORKER1_PORT, "localhost:" + COORDINATOR_PORT);
        Worker worker2 = new Worker("worker-2", worker2Dir, WORKER2_PORT, "localhost:" + COORDINATOR_PORT);

        WorkerServer workerServer1 = new WorkerServer(worker1, WORKER1_PORT, "localhost:" + COORDINATOR_PORT);
        WorkerServer workerServer2 = new WorkerServer(worker2, WORKER2_PORT, "localhost:" + COORDINATOR_PORT);

        workerServer1.start();
        workerServer2.start();

        // === Step 5: Register workers with coordinator ===
        coordinatorServer.registerWorker("worker-1", "localhost", WORKER1_PORT);
        coordinatorServer.registerWorker("worker-2", "localhost", WORKER2_PORT);

        // Give servers time to fully start
        Thread.sleep(500);

        try {
            // === Step 6: Client sends query to coordinator ===
            ManagedChannel clientChannel = ManagedChannelBuilder
                    .forAddress("localhost", COORDINATOR_PORT)
                    .usePlaintext()
                    .build();

            try {
                CoordinatorServiceGrpc.CoordinatorServiceBlockingStub stub =
                        CoordinatorServiceGrpc.newBlockingStub(clientChannel);

                QueryRequest request = QueryRequest.newBuilder()
                        .setSql("SELECT sum(price) FROM orders WHERE price > 250 GROUP BY nation")
                        .setQueryId("test-query-1")
                        .build();

                QueryResult result = stub.executeQuery(request);

                // === Step 7: Verify the merged result ===
                assertNotNull(result);
                assertTrue(result.getRowsReturned() > 0, "Should have results");
                assertEquals(1, result.getBatchesCount(), "Should have exactly 1 result batch");

                // Deserialize the result batch
                RecordBatch batch = result.getBatches(0);
                assertEquals(2, batch.getColumnsCount(), "Batch should have 2 columns (key, value)");

                com.vksql.network.proto.ColumnData keyColumn = batch.getColumns(0);
                com.vksql.network.proto.ColumnData valueColumn = batch.getColumns(1);

                ByteBuffer keyBuffer = keyColumn.getValues().asReadOnlyByteBuffer();
                ByteBuffer valueBuffer = valueColumn.getValues().asReadOnlyByteBuffer();

                Map<Object, Long> actualResult = new HashMap<>();
                int numResults = keyColumn.getNumValues();
                for (int i = 0; i < numResults; i++) {
                    int key = keyBuffer.getInt();
                    long value = valueBuffer.getLong();
                    actualResult.put(key, value);
                }

                // Verify we got the same number of groups
                assertEquals(expectedResult.size(), actualResult.size(),
                        "Should have same number of nation groups. Expected: " + expectedResult.size()
                                + ", got: " + actualResult.size());

                // Verify each group's aggregated value
                for (Map.Entry<Object, Long> entry : expectedResult.entrySet()) {
                    Object key = entry.getKey();
                    Long expectedValue = entry.getValue();
                    Long actualValue = actualResult.get(key);

                    assertNotNull(actualValue,
                            "Result missing group key: " + key + ". Actual keys: " + actualResult.keySet());
                    assertEquals(expectedValue, actualValue,
                            "Mismatch for nation=" + key + ": expected=" + expectedValue
                                    + ", actual=" + actualValue);
                }

            } finally {
                clientChannel.shutdown();
                clientChannel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            }

        } finally {
            // === Step 8: Shut everything down ===
            workerServer1.stop();
            workerServer2.stop();
            coordinatorServer.stop();
        }
    }
}
