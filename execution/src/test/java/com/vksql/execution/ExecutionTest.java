package com.vksql.execution;

import com.vksql.parser.SqlToRelConverter;
import com.vksql.parser.expr.*;
import com.vksql.parser.generated.*;
import com.vksql.parser.plan.*;
import com.vksql.storage.format.*;
import com.vksql.storage.writer.VksqlFileWriter;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionTest {

    @TempDir
    Path tempDir;

    private Schema ordersSchema() {
        return new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("price", DataType.INT64, 1),
            new ColumnDescriptor("quantity", DataType.INT32, 2)
        ));
    }

    private Path writeOrdersTable(Schema schema) throws Exception {
        Path filePath = tempDir.resolve("orders.vkql");
        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow(1, 50L,  10);
            writer.writeRow(2, 150L, 20);
            writer.writeRow(3, 200L, 5);
            writer.writeRow(4, 80L,  15);
            writer.writeRow(5, 300L, 8);
        }
        return filePath;
    }

    @Test
    void scanAllRows() throws Exception {
        Schema schema = ordersSchema();
        Path filePath = writeOrdersTable(schema);

        // Manually build operator: just scan
        Operator scan = new ScanOperator(filePath, schema);
        scan.open();

        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = scan.next()) != null) {
            results.add(row);
        }
        scan.close();

        assertEquals(5, results.size());
        assertEquals(1, results.get(0).get(0));    // id=1
        assertEquals(150L, results.get(1).get(1)); // price=150
    }

    @Test
    void filterRows() throws Exception {
        Schema schema = ordersSchema();
        Path filePath = writeOrdersTable(schema);

        // Scan → Filter(price > 100)
        Operator scan = new ScanOperator(filePath, schema);
        Expr condition = new ComparisonExpr(new ColumnRef("price"), ">", new IntLiteral(100));
        Operator filter = new FilterOperator(scan, condition, schema);

        filter.open();
        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = filter.next()) != null) {
            results.add(row);
        }
        filter.close();

        // Only rows with price > 100: (2,150), (3,200), (5,300)
        assertEquals(3, results.size());
        assertEquals(150L, results.get(0).get(1));
        assertEquals(200L, results.get(1).get(1));
        assertEquals(300L, results.get(2).get(1));
    }

    @Test
    void projectColumns() throws Exception {
        Schema schema = ordersSchema();
        Path filePath = writeOrdersTable(schema);

        // Scan → Project(price only)
        Operator scan = new ScanOperator(filePath, schema);
        Operator project = new ProjectOperator(scan, List.of(new ColumnRef("price")), schema);

        project.open();
        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = project.next()) != null) {
            results.add(row);
        }
        project.close();

        assertEquals(5, results.size());
        assertEquals(1, results.get(0).columnCount()); // only 1 column
        assertEquals(50L, results.get(0).get(0));      // first row price
    }

    @Test
    void fullQuery_selectPriceWhereGt100() throws Exception {
        Schema schema = ordersSchema();
        Path filePath = writeOrdersTable(schema);

        // Build: Scan → Filter(price > 100) → Project(price)
        Operator scan = new ScanOperator(filePath, schema);
        Expr condition = new ComparisonExpr(new ColumnRef("price"), ">", new IntLiteral(100));
        Operator filter = new FilterOperator(scan, condition, schema);
        Operator project = new ProjectOperator(filter, List.of(new ColumnRef("price")), schema);

        project.open();
        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = project.next()) != null) {
            results.add(row);
        }
        project.close();

        // SELECT price FROM orders WHERE price > 100
        // Results: 150, 200, 300
        assertEquals(3, results.size());
        assertEquals(150L, results.get(0).get(0));
        assertEquals(200L, results.get(1).get(0));
        assertEquals(300L, results.get(2).get(0));

        System.out.println("=== SELECT price FROM orders WHERE price > 100 ===");
        for (Row r : results) {
            System.out.println("  price = " + r.get(0));
        }
    }

    @Test
    void hashAggregate_groupByWithSum() throws Exception {
        // Schema: (nation STRING, price INT64)
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("nation", DataType.STRING, 0),
            new ColumnDescriptor("price", DataType.INT64, 1)
        ));

        Path filePath = tempDir.resolve("nations.vkql");
        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow("USA", 100L);
            writer.writeRow("UK",  200L);
            writer.writeRow("USA", 150L);
            writer.writeRow("UK",  50L);
            writer.writeRow("USA", 50L);
        }

        // SELECT nation, sum(price) FROM nations GROUP BY nation
        Operator scan = new ScanOperator(filePath, schema);
        List<Expr> groupBy = List.of(new ColumnRef("nation"));
        List<FunctionCall> aggs = List.of(new FunctionCall("sum", List.of(new ColumnRef("price"))));
        Operator aggregate = new HashAggregateOperator(scan, schema, groupBy, aggs);

        aggregate.open();
        Map<Object, Long> results = new java.util.HashMap<>();
        Row row;
        while ((row = aggregate.next()) != null) {
            results.put(row.get(0), (long) row.get(1));
        }
        aggregate.close();

        assertEquals(2, results.size());
        assertEquals(300L, results.get("USA"));  // 100 + 150 + 50
        assertEquals(250L, results.get("UK"));   // 200 + 50

        System.out.println("=== SELECT nation, sum(price) GROUP BY nation ===");
        results.forEach((k, v) -> System.out.println("  " + k + " → " + v));
    }

    @Test
    void hashJoin_ordersWithCustomers() throws Exception {
        // Orders: (order_id INT32, cust_id INT32, price INT64)
        Schema ordersSchema = new Schema(List.of(
            new ColumnDescriptor("order_id", DataType.INT32, 0),
            new ColumnDescriptor("cust_id", DataType.INT32, 1),
            new ColumnDescriptor("price", DataType.INT64, 2)
        ));

        // Customers: (id INT32, name STRING)
        Schema customersSchema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("name", DataType.STRING, 1)
        ));

        Path ordersPath = tempDir.resolve("orders_join.vkql");
        try (var writer = new VksqlFileWriter(ordersPath, ordersSchema)) {
            writer.writeRow(101, 1, 500L);
            writer.writeRow(102, 2, 300L);
            writer.writeRow(103, 1, 200L);
            writer.writeRow(104, 3, 100L);  // cust_id=3 has no customer → skipped
        }

        Path customersPath = tempDir.resolve("customers_join.vkql");
        try (var writer = new VksqlFileWriter(customersPath, customersSchema)) {
            writer.writeRow(1, "alice");
            writer.writeRow(2, "bob");
        }

        // JOIN orders ON orders.cust_id = customers.id
        Operator ordersScan = new ScanOperator(ordersPath, ordersSchema);
        Operator customersScan = new ScanOperator(customersPath, customersSchema);
        Operator join = new HashJoinOperator(
            ordersScan, customersScan,
            ordersSchema, customersSchema,
            "cust_id", "id"
        );

        join.open();
        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            results.add(row);
        }
        join.close();

        // Should get 3 rows (order 104 has no matching customer)
        assertEquals(3, results.size());

        // Each row has 5 columns: order_id, cust_id, price, id, name
        assertEquals(5, results.get(0).columnCount());

        // Order 101, cust_id=1, price=500, customer id=1, name=alice
        assertEquals(101, results.get(0).get(0));
        assertEquals("alice", results.get(0).get(4));

        // Order 102, cust_id=2, price=300, customer id=2, name=bob
        assertEquals(102, results.get(1).get(0));
        assertEquals("bob", results.get(1).get(4));

        // Order 103, cust_id=1, price=200, customer id=1, name=alice
        assertEquals(103, results.get(2).get(0));
        assertEquals("alice", results.get(2).get(4));

        System.out.println("=== orders JOIN customers ON cust_id = id ===");
        for (Row r : results) {
            System.out.println("  " + r);
        }
    }

    @Test
    void limitRows() throws Exception {
        Schema schema = ordersSchema();
        Path filePath = writeOrdersTable(schema);

        // Scan → Limit(2)
        Operator scan = new ScanOperator(filePath, schema);
        Operator limit = new LimitOperator(scan, 2);

        limit.open();
        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = limit.next()) != null) {
            results.add(row);
        }
        limit.close();

        assertEquals(2, results.size());
    }
}
