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
