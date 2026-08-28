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

    // ==================== Q12 & Q14 DATA GENERATION ====================

    private static final int Q12_ORDERS_ROWS = 2_000_000;
    private static final int Q12_LINEITEM_ROWS = 10_000_000;
    private static final int Q14_PART_ROWS = 500_000;

    // Q12 orders schema: orderkey INT32, priority INT32
    private Schema ordersSchema() {
        return new Schema(List.of(
            new ColumnDescriptor("orderkey", DataType.INT32, 0),
            new ColumnDescriptor("priority", DataType.INT32, 1)
        ));
    }

    // Q12 lineitem schema: orderkey INT32, shipmode INT32, receiptdate INT32, price INT64
    private Schema lineitemQ12Schema() {
        return new Schema(List.of(
            new ColumnDescriptor("orderkey", DataType.INT32, 0),
            new ColumnDescriptor("shipmode", DataType.INT32, 1),
            new ColumnDescriptor("receiptdate", DataType.INT32, 2),
            new ColumnDescriptor("price", DataType.INT64, 3)
        ));
    }

    // Q14 lineitem schema: partkey INT32, price INT64, discount INT32, shipdate INT32
    private Schema lineitemQ14Schema() {
        return new Schema(List.of(
            new ColumnDescriptor("partkey", DataType.INT32, 0),
            new ColumnDescriptor("price", DataType.INT64, 1),
            new ColumnDescriptor("discount", DataType.INT32, 2),
            new ColumnDescriptor("shipdate", DataType.INT32, 3)
        ));
    }

    // Q14 part schema: partkey INT32, is_promo INT32
    private Schema partSchema() {
        return new Schema(List.of(
            new ColumnDescriptor("partkey", DataType.INT32, 0),
            new ColumnDescriptor("is_promo", DataType.INT32, 1)
        ));
    }

    private int randomReceiptdate(Random rng) {
        int year = 1994 + rng.nextInt(5);   // 1994-1998
        int month = 1 + rng.nextInt(12);
        int maxDay;
        switch (month) {
            case 2 -> maxDay = (year % 4 == 0) ? 29 : 28;
            case 4, 6, 9, 11 -> maxDay = 30;
            default -> maxDay = 31;
        }
        int day = 1 + rng.nextInt(maxDay);
        return year * 10000 + month * 100 + day;
    }

    private Path generateOrdersData() throws Exception {
        Schema schema = ordersSchema();
        Path filePath = tempDir.resolve("orders.vkql");
        Random rng = new Random(SEED);

        System.out.println("Generating " + (Q12_ORDERS_ROWS / 1_000_000) + "M rows of orders data...");
        long writeStart = System.nanoTime();

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < Q12_ORDERS_ROWS; i++) {
                int priority = rng.nextInt(5); // 0-4
                writer.writeRow(i, priority);
            }
        }

        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("Orders data generation: " + writeMs + " ms");
        return filePath;
    }

    private Path generateLineitemQ12Data() throws Exception {
        Schema schema = lineitemQ12Schema();
        Path filePath = tempDir.resolve("lineitem_q12.vkql");
        Random rng = new Random(SEED + 1);

        System.out.println("Generating " + (Q12_LINEITEM_ROWS / 1_000_000) + "M rows of lineitem (Q12) data...");
        long writeStart = System.nanoTime();

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < Q12_LINEITEM_ROWS; i++) {
                int orderkey = i % Q12_ORDERS_ROWS;
                int shipmode = rng.nextInt(5);              // 0-4
                int receiptdate = randomReceiptdate(rng);   // 19940101-19981231
                long price = 1000L + rng.nextInt(49001);    // 1000-50000
                writer.writeRow(orderkey, shipmode, receiptdate, price);
            }
        }

        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("Lineitem Q12 data generation: " + writeMs + " ms");
        return filePath;
    }

    private Path generateLineitemQ14Data() throws Exception {
        Schema schema = lineitemQ14Schema();
        Path filePath = tempDir.resolve("lineitem_q14.vkql");
        Random rng = new Random(SEED + 2);

        System.out.println("Generating " + (Q12_LINEITEM_ROWS / 1_000_000) + "M rows of lineitem (Q14) data...");
        long writeStart = System.nanoTime();

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < Q12_LINEITEM_ROWS; i++) {
                int partkey = i % Q14_PART_ROWS;
                long price = 1000L + rng.nextInt(49001);    // 1000-50000
                int discount = 1 + rng.nextInt(10);         // 1-10
                int shipdate = randomShipdate(rng);         // 19940101-19981231
                writer.writeRow(partkey, price, discount, shipdate);
            }
        }

        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("Lineitem Q14 data generation: " + writeMs + " ms");
        return filePath;
    }

    private Path generatePartData() throws Exception {
        Schema schema = partSchema();
        Path filePath = tempDir.resolve("part.vkql");
        Random rng = new Random(SEED + 3);

        System.out.println("Generating " + (Q14_PART_ROWS / 1_000) + "K rows of part data...");
        long writeStart = System.nanoTime();

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < Q14_PART_ROWS; i++) {
                int isPromo = (rng.nextInt(100) < 20) ? 1 : 0; // 20% promo
                writer.writeRow(i, isPromo);
            }
        }

        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("Part data generation: " + writeMs + " ms");
        return filePath;
    }

    /**
     * TPC-H Q12 — Shipping Modes and Order Priority (simplified)
     *
     * SELECT l_shipmode,
     *        sum(case when o_priority <= 1 then 1 else 0 end) as high_line_count,
     *        sum(case when o_priority > 1 then 1 else 0 end) as low_line_count
     * FROM orders JOIN lineitem ON o_orderkey = l_orderkey
     * WHERE l_shipmode IN (1, 2)
     *   AND l_receiptdate >= 19940101
     *   AND l_receiptdate < 19950101
     * GROUP BY l_shipmode
     *
     * Build hash map from orders (orderkey -> priority), scan lineitem with filter,
     * probe hash map, aggregate by shipmode.
     */
    @Test
    void tpchQ12_shippingModesAndOrderPriority() throws Exception {
        Path ordersPath = generateOrdersData();
        Path lineitemPath = generateLineitemQ12Data();

        System.out.println();
        System.out.println("=== TPC-H Q12: Shipping Modes and Order Priority ===");
        System.out.println("Join: orders.orderkey = lineitem.orderkey");
        System.out.println("Filter: shipmode IN (1,2) AND receiptdate >= 19940101 AND receiptdate < 19950101");
        System.out.println("Group by: shipmode -> count high priority (<=1), low priority (>1)");
        System.out.println();

        long execStart = System.nanoTime();

        // Phase 1: Build hash map from orders (orderkey -> priority)
        HashMap<Integer, Integer> orderPriority = new HashMap<>(Q12_ORDERS_ROWS * 2);
        MappedFileReader ordersReader = new MappedFileReader(ordersPath);
        try {
            FileFooter ordersFooter = ordersReader.getFooter();
            for (RowGroupMetadata rg : ordersFooter.rows()) {
                int[] orderkeys = ordersReader.readIntColumnDirect(rg, 0);
                int[] priorities = ordersReader.readIntColumnDirect(rg, 1);
                for (int i = 0; i < orderkeys.length; i++) {
                    orderPriority.put(orderkeys[i], priorities[i]);
                }
            }
        } finally {
            ordersReader.close();
        }

        long buildMs = (System.nanoTime() - execStart) / 1_000_000;
        System.out.println("Hash map build (orders): " + buildMs + " ms (" + orderPriority.size() + " entries)");

        // Phase 2: Scan lineitem, filter, probe, aggregate
        long probeStart = System.nanoTime();
        int totalRows = 0;
        int matchingRows = 0;

        // Aggregates per shipmode: [high_line_count, low_line_count]
        HashMap<Integer, long[]> groups = new HashMap<>();

        MappedFileReader lineitemReader = new MappedFileReader(lineitemPath);
        try {
            FileFooter lineitemFooter = lineitemReader.getFooter();
            for (RowGroupMetadata rg : lineitemFooter.rows()) {
                int[] orderkeys = lineitemReader.readIntColumnDirect(rg, 0);
                int[] shipmodes = lineitemReader.readIntColumnDirect(rg, 1);
                int[] receiptdates = lineitemReader.readIntColumnDirect(rg, 2);

                int rgRows = orderkeys.length;
                totalRows += rgRows;

                // TIGHT LOOP — filter + hash probe + aggregate
                for (int i = 0; i < rgRows; i++) {
                    int sm = shipmodes[i];
                    int rd = receiptdates[i];

                    // Filter: shipmode IN (1, 2) AND receiptdate in [19940101, 19950101)
                    if ((sm == 1 || sm == 2) && rd >= 19940101 && rd < 19950101) {
                        Integer priority = orderPriority.get(orderkeys[i]);
                        if (priority != null) {
                            long[] agg = groups.get(sm);
                            if (agg == null) {
                                agg = new long[2]; // [high_line_count, low_line_count]
                                groups.put(sm, agg);
                            }
                            if (priority <= 1) {
                                agg[0]++;
                            } else {
                                agg[1]++;
                            }
                            matchingRows++;
                        }
                    }
                }
            }
        } finally {
            lineitemReader.close();
        }

        long execMs = (System.nanoTime() - execStart) / 1_000_000;
        long probeMs = (System.nanoTime() - probeStart) / 1_000_000;
        double throughputMRows = (double) totalRows / Math.max(probeMs, 1) / 1000.0;

        String[] shipmodeNames = {"N/A", "MAIL", "SHIP"};
        System.out.println();
        System.out.println("=== TPC-H Q12 RESULTS ===");
        System.out.println("Lineitem rows scanned: " + totalRows);
        System.out.println("Joined+filtered rows:  " + matchingRows);
        System.out.println();
        System.out.printf("%-12s %18s %18s%n", "shipmode", "high_line_count", "low_line_count");
        System.out.println("-".repeat(50));
        for (var entry : groups.entrySet()) {
            int sm = entry.getKey();
            long[] agg = entry.getValue();
            String name = (sm >= 0 && sm < shipmodeNames.length) ? shipmodeNames[sm] : String.valueOf(sm);
            System.out.printf("%-12s %18d %18d%n", name, agg[0], agg[1]);
        }
        System.out.println();
        System.out.println("Hash build time:  " + buildMs + " ms");
        System.out.println("Probe+agg time:   " + probeMs + " ms");
        System.out.println("Total exec time:  " + execMs + " ms");
        System.out.printf("Throughput:       %.1f M rows/sec (probe phase)%n", throughputMRows);
        System.out.println();

        // Sanity checks
        assert totalRows == Q12_LINEITEM_ROWS : "Expected " + Q12_LINEITEM_ROWS + " rows, got " + totalRows;
        assert groups.size() == 2 : "Expected 2 shipmode groups (1=MAIL, 2=SHIP), got " + groups.size();
        assert matchingRows > 0 : "No matching rows — check filter/join logic";

        // Verify high + low counts sum correctly per group
        for (var entry : groups.entrySet()) {
            long[] agg = entry.getValue();
            assert agg[0] > 0 : "high_line_count should be > 0 for shipmode " + entry.getKey();
            assert agg[1] > 0 : "low_line_count should be > 0 for shipmode " + entry.getKey();
        }
    }

    /**
     * TPC-H Q14 — Promotion Effect (simplified)
     *
     * SELECT 100.0 * sum(case when is_promo = 1 then price * (100 - discount) else 0 end)
     *        / sum(price * (100 - discount)) as promo_revenue
     * FROM lineitem JOIN part ON l_partkey = p_partkey
     * WHERE l_shipdate >= 19950901 AND l_shipdate < 19951001
     *
     * Build hash map from part (partkey -> is_promo), scan lineitem with date filter,
     * probe hash map, compute promo vs total revenue.
     */
    @Test
    void tpchQ14_promotionEffect() throws Exception {
        Path lineitemPath = generateLineitemQ14Data();
        Path partPath = generatePartData();

        System.out.println();
        System.out.println("=== TPC-H Q14: Promotion Effect ===");
        System.out.println("Join: lineitem.partkey = part.partkey");
        System.out.println("Filter: shipdate >= 19950901 AND shipdate < 19951001");
        System.out.println("Compute: 100.0 * sum(promo_revenue) / sum(total_revenue)");
        System.out.println();

        long execStart = System.nanoTime();

        // Phase 1: Build hash map from part (partkey -> is_promo)
        HashMap<Integer, Integer> partPromo = new HashMap<>(Q14_PART_ROWS * 2);
        MappedFileReader partReader = new MappedFileReader(partPath);
        try {
            FileFooter partFooter = partReader.getFooter();
            for (RowGroupMetadata rg : partFooter.rows()) {
                int[] partkeys = partReader.readIntColumnDirect(rg, 0);
                int[] isPromos = partReader.readIntColumnDirect(rg, 1);
                for (int i = 0; i < partkeys.length; i++) {
                    partPromo.put(partkeys[i], isPromos[i]);
                }
            }
        } finally {
            partReader.close();
        }

        long buildMs = (System.nanoTime() - execStart) / 1_000_000;
        System.out.println("Hash map build (part): " + buildMs + " ms (" + partPromo.size() + " entries)");

        // Phase 2: Scan lineitem, filter by date, probe part table, compute revenue
        long probeStart = System.nanoTime();
        int totalRows = 0;
        int matchingRows = 0;
        long promoRevenue = 0;
        long totalRevenue = 0;

        MappedFileReader lineitemReader = new MappedFileReader(lineitemPath);
        try {
            FileFooter lineitemFooter = lineitemReader.getFooter();
            for (RowGroupMetadata rg : lineitemFooter.rows()) {
                int[] partkeys = lineitemReader.readIntColumnDirect(rg, 0);
                long[] prices = lineitemReader.readLongColumnDirect(rg, 1);
                int[] discounts = lineitemReader.readIntColumnDirect(rg, 2);
                int[] shipdates = lineitemReader.readIntColumnDirect(rg, 3);

                int rgRows = partkeys.length;
                totalRows += rgRows;

                // TIGHT LOOP — filter + hash probe + revenue computation
                for (int i = 0; i < rgRows; i++) {
                    int sd = shipdates[i];

                    // Filter: shipdate in [19950901, 19951001)
                    if (sd >= 19950901 && sd < 19951001) {
                        Integer isPromo = partPromo.get(partkeys[i]);
                        if (isPromo != null) {
                            long revenue = prices[i] * (100 - discounts[i]);
                            totalRevenue += revenue;
                            if (isPromo == 1) {
                                promoRevenue += revenue;
                            }
                            matchingRows++;
                        }
                    }
                }
            }
        } finally {
            lineitemReader.close();
        }

        long execMs = (System.nanoTime() - execStart) / 1_000_000;
        long probeMs = (System.nanoTime() - probeStart) / 1_000_000;
        double throughputMRows = (double) totalRows / Math.max(probeMs, 1) / 1000.0;

        double promoPercentage = (totalRevenue > 0) ? 100.0 * promoRevenue / totalRevenue : 0.0;

        System.out.println();
        System.out.println("=== TPC-H Q14 RESULTS ===");
        System.out.println("Lineitem rows scanned: " + totalRows);
        System.out.println("Joined+filtered rows:  " + matchingRows);
        System.out.println("Promo revenue:         " + promoRevenue);
        System.out.println("Total revenue:         " + totalRevenue);
        System.out.printf("Promo percentage:      %.2f%%%n", promoPercentage);
        System.out.println();
        System.out.println("Hash build time:  " + buildMs + " ms");
        System.out.println("Probe+agg time:   " + probeMs + " ms");
        System.out.println("Total exec time:  " + execMs + " ms");
        System.out.printf("Throughput:       %.1f M rows/sec (probe phase)%n", throughputMRows);
        System.out.println();

        // Sanity checks
        assert totalRows == Q12_LINEITEM_ROWS : "Expected " + Q12_LINEITEM_ROWS + " rows, got " + totalRows;
        assert matchingRows > 0 : "No matching rows — check date filter/join logic";
        assert totalRevenue > 0 : "Total revenue should be positive";
        assert promoRevenue > 0 : "Promo revenue should be positive (20% promo rate)";
        assert promoRevenue <= totalRevenue : "Promo revenue cannot exceed total revenue";
        // With ~20% promo rate and random data, expect ~15-25% promo revenue
        assert promoPercentage > 5.0 && promoPercentage < 40.0 :
            "Promo percentage " + promoPercentage + "% seems unreasonable for 20% promo rate";
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
