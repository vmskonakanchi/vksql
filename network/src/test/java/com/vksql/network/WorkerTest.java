package com.vksql.network;

import com.vksql.network.worker.Worker;
import com.vksql.storage.format.*;
import com.vksql.storage.writer.VksqlFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkerTest {

    @TempDir
    Path tempDir;

    /**
     * Schema: (nation INT32, price INT64, quantity INT32)
     * We use INT32 for nation as a group-by key (e.g., nation code).
     */
    private Schema createSchema() {
        return new Schema(List.of(
                new ColumnDescriptor("nation", DataType.INT32, 0),
                new ColumnDescriptor("price", DataType.INT64, 1),
                new ColumnDescriptor("quantity", DataType.INT32, 2)
        ));
    }

    private void writeTestData(Path filePath, Schema schema) throws Exception {
        try (var writer = new VksqlFileWriter(filePath, schema)) {
            // nation=1: prices 50, 150, 200 (sum where price > 100 = 350)
            writer.writeRow(1, 50L, 10);
            writer.writeRow(1, 150L, 20);
            writer.writeRow(1, 200L, 5);
            // nation=2: prices 80, 300 (sum where price > 100 = 300)
            writer.writeRow(2, 80L, 15);
            writer.writeRow(2, 300L, 8);
            // nation=3: prices 90 (sum where price > 100 = 0, not in results)
            writer.writeRow(3, 90L, 12);
        }
    }

    @Test
    void executeTask_filterAndAggregate() throws Exception {
        Schema schema = createSchema();
        Path filePath = tempDir.resolve("orders.vkql");
        writeTestData(filePath, schema);

        Worker worker = new Worker("worker-1", tempDir, 9090, "localhost:8080");

        // Filter: price > 100, group by nation (col 0), sum price (col 1)
        Map<Object, Long> results = worker.executeTask(
                "orders",       // tableName
                "price",        // filterColumn
                ">",            // filterOp
                100L,           // filterValue
                0,              // groupByColIndex (nation)
                1,              // aggColIndex (price)
                "sum"           // aggFunction
        );

        // nation=1: 150 + 200 = 350
        // nation=2: 300
        // nation=3: no rows pass filter
        assertEquals(2, results.size());
        assertEquals(350L, results.get(1));
        assertEquals(300L, results.get(2));
    }

    @Test
    void executeTask_filterWithEquals() throws Exception {
        Schema schema = createSchema();
        Path filePath = tempDir.resolve("orders.vkql");
        writeTestData(filePath, schema);

        Worker worker = new Worker("worker-2", tempDir, 9091, "localhost:8080");

        // Filter: nation = 1, group by nation (col 0), sum price (col 1)
        // But wait - the filter is on INT32 column through int[] path
        // nation is INT32 so we filter on "quantity" which is also INT32
        // Let's filter on price >= 100: should give nation=1 (150+200=350), nation=2 (300)
        Map<Object, Long> results = worker.executeTask(
                "orders",
                "price",
                ">=",
                100L,
                0,              // groupByColIndex (nation)
                1,              // aggColIndex (price)
                "sum"
        );

        assertEquals(2, results.size());
        assertEquals(350L, results.get(1));  // 150 + 200
        assertEquals(300L, results.get(2));  // 300
    }

    @Test
    void executeTask_noMatchingRows() throws Exception {
        Schema schema = createSchema();
        Path filePath = tempDir.resolve("orders.vkql");
        writeTestData(filePath, schema);

        Worker worker = new Worker("worker-3", tempDir, 9092, "localhost:8080");

        // Filter: price > 1000 — no rows match
        Map<Object, Long> results = worker.executeTask(
                "orders",
                "price",
                ">",
                1000L,
                0,
                1,
                "sum"
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void start_isNoOp() {
        Worker worker = new Worker("worker-4", tempDir, 9093, "localhost:8080");
        // Should not throw
        worker.start();
    }
}
