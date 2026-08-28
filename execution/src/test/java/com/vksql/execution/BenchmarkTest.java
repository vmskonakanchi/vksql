package com.vksql.execution;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.*;
import com.vksql.storage.writer.VksqlFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * Simple benchmark: measures rows/sec for the current Volcano (row-at-a-time) engine.
 * Run this BEFORE and AFTER vectorization to see the difference.
 */
class BenchmarkTest {

    @TempDir
    Path tempDir;

    @Test
    void benchmark_scanFilterAggregate_1M_rows() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("price", DataType.INT64, 1),
            new ColumnDescriptor("nation", DataType.INT32, 2)
        ));

        int numRows = 100_000;
        Path filePath = tempDir.resolve("bench.vkql");

        // Write 1M rows
        System.out.println("Writing " + numRows + " rows...");
        long writeStart = System.nanoTime();
        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < numRows; i++) {
                writer.writeRow(i, (long) (i % 500), i % 10);
            }
        }
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("Write time: " + writeMs + " ms");
        System.out.println("Write speed: " + (numRows / Math.max(writeMs, 1)) + "K rows/sec");

        // Benchmark: SELECT nation, sum(price) FROM bench WHERE price > 250 GROUP BY nation
        System.out.println("\nRunning: SELECT nation, sum(price) WHERE price > 250 GROUP BY nation");

        long execStart = System.nanoTime();

        Operator scan = new ScanOperator(filePath, schema);
        Operator filter = new FilterOperator(scan,
            new ComparisonExpr(new ColumnRef("price"), ">", new IntLiteral(250)),
            schema);
        Operator aggregate = new HashAggregateOperator(filter, schema,
            List.of(new ColumnRef("nation")),
            List.of(new FunctionCall("sum", List.of(new ColumnRef("price")))));

        aggregate.open();
        int resultCount = 0;
        Row row;
        while ((row = aggregate.next()) != null) {
            resultCount++;
        }
        aggregate.close();

        long execMs = (System.nanoTime() - execStart) / 1_000_000;
        long rowsPerSec = numRows * 1000L / Math.max(execMs, 1);

        System.out.println("\n=== BENCHMARK RESULTS (Volcano / Row-at-a-time) ===");
        System.out.println("Rows processed: " + numRows);
        System.out.println("Result groups:  " + resultCount);
        System.out.println("Execution time: " + execMs + " ms");
        System.out.println("Throughput:     " + rowsPerSec + " rows/sec");
        System.out.println("                " + (rowsPerSec / 1_000_000) + "M rows/sec");
    }
}
