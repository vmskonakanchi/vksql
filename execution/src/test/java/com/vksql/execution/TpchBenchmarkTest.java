package com.vksql.execution;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.MappedFileReader;
import com.vksql.storage.writer.VksqlFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * TPC-H Q1 and Q6 benchmarks using direct tight loops over columnar arrays.
 *
 * These bypass the operator framework to show raw engine throughput —
 * memory-mapped columnar reads + tight compute loops, no per-row overhead.
 *
 * Schema (lineitem-like):
 *   orderkey  INT32  - sequential 0..N
 *   quantity  INT32  - random 1-50
 *   price     INT64  - random 1000-50000 (cents)
 *   discount  INT32  - random 1-10 (percent)
 *   returnflag INT32 - random 0-2 (A=0, F=1, R=2)
 *   shipdate  INT32  - random 19940101-19981231 (YYYYMMDD)
 */
class TpchBenchmarkTest {

    private static final int NUM_ROWS = 10_000_000;
    private static final long SEED = 42;

    // Column indices
    private static final int COL_ORDERKEY = 0;
    private static final int COL_QUANTITY = 1;
    private static final int COL_PRICE = 2;
    private static final int COL_DISCOUNT = 3;
    private static final int COL_RETURNFLAG = 4;
    private static final int COL_SHIPDATE = 5;

    @TempDir
    Path tempDir;

    private Schema lineitemSchema() {
        return new Schema(List.of(
            new ColumnDescriptor("orderkey", DataType.INT32, 0),
            new ColumnDescriptor("quantity", DataType.INT32, 1),
            new ColumnDescriptor("price", DataType.INT64, 2),
            new ColumnDescriptor("discount", DataType.INT32, 3),
            new ColumnDescriptor("returnflag", DataType.INT32, 4),
            new ColumnDescriptor("shipdate", DataType.INT32, 5)
        ));
    }

    /**
     * Generate a random shipdate as YYYYMMDD integer between 19940101 and 19981231.
     */
    private int randomShipdate(Random rng) {
        int year = 1994 + rng.nextInt(5);   // 1994-1998
        int month = 1 + rng.nextInt(12);     // 1-12
        int maxDay;
        switch (month) {
            case 2 -> maxDay = (year % 4 == 0) ? 29 : 28;
            case 4, 6, 9, 11 -> maxDay = 30;
            default -> maxDay = 31;
        }
        int day = 1 + rng.nextInt(maxDay);
        return year * 10000 + month * 100 + day;
    }

    private Path generateData() throws Exception {
        Schema schema = lineitemSchema();
        Path filePath = tempDir.resolve("lineitem.vkql");
        Random rng = new Random(SEED);

        System.out.println("Generating " + (NUM_ROWS / 1_000_000) + "M rows of TPC-H lineitem data...");
        long writeStart = System.nanoTime();

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < NUM_ROWS; i++) {
                int quantity = 1 + rng.nextInt(50);         // 1-50
                long price = 1000L + rng.nextInt(49001);    // 1000-50000
                int discount = 1 + rng.nextInt(10);         // 1-10
                int returnflag = rng.nextInt(3);            // 0-2
                int shipdate = randomShipdate(rng);

                writer.writeRow(i, quantity, price, discount, returnflag, shipdate);
            }
        }

        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("Data generation: " + writeMs + " ms (" + (NUM_ROWS / Math.max(writeMs, 1)) + "K rows/sec)");
        System.out.println();
        return filePath;
    }

    /**
     * TPC-H Q6 — Forecasting Revenue Change (simplified)
     *
     * SELECT sum(price * discount) as revenue
     * FROM lineitem
     * WHERE shipdate >= 19940101
     *   AND shipdate < 19950101
     *   AND discount >= 5
     *   AND discount <= 7
     *   AND quantity < 24
     *
     * Direct tight loop over columnar arrays — no operator overhead.
     */
    @Test
    void tpchQ6_forecastingRevenueChange() throws Exception {
        Path filePath = generateData();
        Schema schema = lineitemSchema();

        System.out.println("=== TPC-H Q6: Forecasting Revenue Change ===");
        System.out.println("Filter: shipdate >= 19940101 AND shipdate < 19950101 AND discount BETWEEN 5 AND 7 AND quantity < 24");
        System.out.println("Aggregate: sum(price * discount)");
        System.out.println();

        long execStart = System.nanoTime();
        long revenue = 0;
        int matchingRows = 0;
        int totalRows = 0;

        MappedFileReader reader = new MappedFileReader(filePath);
        try {
            FileFooter footer = reader.getFooter();

            for (RowGroupMetadata rg : footer.rows()) {
                // Read all needed columns directly into typed arrays
                int[] quantity = reader.readIntColumnDirect(rg, COL_QUANTITY);
                long[] price = reader.readLongColumnDirect(rg, COL_PRICE);
                int[] discount = reader.readIntColumnDirect(rg, COL_DISCOUNT);
                int[] shipdate = reader.readIntColumnDirect(rg, COL_SHIPDATE);

                int rgRows = quantity.length;
                totalRows += rgRows;

                // TIGHT LOOP — this is where all the time is spent
                for (int i = 0; i < rgRows; i++) {
                    int sd = shipdate[i];
                    int d = discount[i];
                    int q = quantity[i];

                    if (sd >= 19940101 && sd < 19950101 && d >= 5 && d <= 7 && q < 24) {
                        revenue += price[i] * d;
                        matchingRows++;
                    }
                }
            }
        } finally {
            reader.close();
        }

        long execMs = (System.nanoTime() - execStart) / 1_000_000;
        double throughputMRows = (double) totalRows / Math.max(execMs, 1) / 1000.0;

        System.out.println("=== TPC-H Q6 RESULTS ===");
        System.out.println("Rows processed:   " + totalRows);
        System.out.println("Rows matching:    " + matchingRows);
        System.out.println("Revenue (sum):    " + revenue);
        System.out.println("Execution time:   " + execMs + " ms");
        System.out.printf("Throughput:       %.1f M rows/sec%n", throughputMRows);
        System.out.println();

        // Sanity check — with random data, expect ~3% selectivity
        // (1/5 years) * (3/10 discount range) * (23/50 quantity) ≈ 2.8%
        assert totalRows == NUM_ROWS : "Expected " + NUM_ROWS + " rows, got " + totalRows;
        assert matchingRows > 0 : "No matching rows — check filter logic";
        assert revenue > 0 : "Revenue should be positive";
    }

    /**
     * TPC-H Q1 — Pricing Summary Report (simplified)
     *
     * SELECT returnflag, sum(quantity), sum(price), count(*)
     * FROM lineitem
     * WHERE shipdate <= 19980902
     * GROUP BY returnflag
     *
     * Direct tight loop with HashMap for group-by aggregation.
     */
    @Test
    void tpchQ1_pricingSummaryReport() throws Exception {
        Path filePath = generateData();
        Schema schema = lineitemSchema();

        System.out.println("=== TPC-H Q1: Pricing Summary Report ===");
        System.out.println("Filter: shipdate <= 19980902");
        System.out.println("Group by: returnflag");
        System.out.println("Aggregate: sum(quantity), sum(price), count(*)");
        System.out.println();

        long execStart = System.nanoTime();
        int totalRows = 0;
        int matchingRows = 0;

        // Group aggregates: returnflag -> [sum_quantity, sum_price, count]
        HashMap<Integer, long[]> groups = new HashMap<>();

        MappedFileReader reader = new MappedFileReader(filePath);
        try {
            FileFooter footer = reader.getFooter();

            for (RowGroupMetadata rg : footer.rows()) {
                // Read all needed columns
                int[] quantity = reader.readIntColumnDirect(rg, COL_QUANTITY);
                long[] price = reader.readLongColumnDirect(rg, COL_PRICE);
                int[] returnflag = reader.readIntColumnDirect(rg, COL_RETURNFLAG);
                int[] shipdate = reader.readIntColumnDirect(rg, COL_SHIPDATE);

                int rgRows = quantity.length;
                totalRows += rgRows;

                // TIGHT LOOP — filter + group-by aggregate
                for (int i = 0; i < rgRows; i++) {
                    if (shipdate[i] <= 19980902) {
                        int flag = returnflag[i];
                        long[] agg = groups.get(flag);
                        if (agg == null) {
                            agg = new long[3]; // [sum_quantity, sum_price, count]
                            groups.put(flag, agg);
                        }
                        agg[0] += quantity[i];
                        agg[1] += price[i];
                        agg[2]++;
                        matchingRows++;
                    }
                }
            }
        } finally {
            reader.close();
        }

        long execMs = (System.nanoTime() - execStart) / 1_000_000;
        double throughputMRows = (double) totalRows / Math.max(execMs, 1) / 1000.0;

        String[] flagNames = {"A", "F", "R"};
        System.out.println("=== TPC-H Q1 RESULTS ===");
        System.out.println("Rows processed:   " + totalRows);
        System.out.println("Rows matching:    " + matchingRows);
        System.out.println();
        System.out.printf("%-12s %15s %18s %12s%n", "returnflag", "sum_quantity", "sum_price", "count");
        System.out.println("-".repeat(60));
        for (var entry : groups.entrySet()) {
            int flag = entry.getKey();
            long[] agg = entry.getValue();
            String name = (flag >= 0 && flag < flagNames.length) ? flagNames[flag] : String.valueOf(flag);
            System.out.printf("%-12s %15d %18d %12d%n", name, agg[0], agg[1], agg[2]);
        }
        System.out.println();
        System.out.println("Execution time:   " + execMs + " ms");
        System.out.printf("Throughput:       %.1f M rows/sec%n", throughputMRows);
        System.out.println();

        // Sanity checks
        assert totalRows == NUM_ROWS : "Expected " + NUM_ROWS + " rows, got " + totalRows;
        assert groups.size() == 3 : "Expected 3 groups (A, F, R), got " + groups.size();
        assert matchingRows > 0 : "No matching rows — check filter logic";

        // Verify counts sum to matchingRows
        long totalCount = groups.values().stream().mapToLong(a -> a[2]).sum();
        assert totalCount == matchingRows : "Group counts don't sum to matching rows";
    }
}
