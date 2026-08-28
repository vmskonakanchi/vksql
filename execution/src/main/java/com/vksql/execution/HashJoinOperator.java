package com.vksql.execution;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.Schema;

import java.util.*;

/**
 * Hash Join: combines two tables on a join condition.
 *
 * Algorithm:
 * 1. BUILD phase: pull all rows from the RIGHT (smaller) table, put in HashMap keyed by join column
 * 2. PROBE phase: pull rows from LEFT (larger) table one by one, look up in HashMap
 * 3. If match found → output combined row (left columns + right columns)
 *
 * Example: orders JOIN customers ON orders.cust_id = customers.id
 *   Build: HashMap { 1 → (1, "alice"), 2 → (2, "bob") }   (customers)
 *   Probe: order row (101, 1, 50) → lookup key 1 → found (1, "alice") → output (101, 1, 50, 1, "alice")
 */
public class HashJoinOperator implements Operator {
    private final Operator leftInput;   // probe side (usually larger)
    private final Operator rightInput;  // build side (usually smaller)
    private final Schema leftSchema;
    private final Schema rightSchema;
    private final String leftColumn;    // join column in left table
    private final String rightColumn;   // join column in right table

    private Map<Object, List<Row>> buildTable; // key → matching rows from right

    public HashJoinOperator(Operator leftInput, Operator rightInput,
                            Schema leftSchema, Schema rightSchema,
                            String leftColumn, String rightColumn) {
        this.leftInput = leftInput;
        this.rightInput = rightInput;
        this.leftSchema = leftSchema;
        this.rightSchema = rightSchema;
        this.leftColumn = leftColumn;
        this.rightColumn = rightColumn;
    }

    @Override
    public void open() {
        leftInput.open();
        rightInput.open();
        buildTable = new HashMap<>();

        // BUILD phase: put all right-side rows into the map
        int rightKeyIndex = findColumnIndex(rightSchema, rightColumn);
        Row row;
        while ((row = rightInput.next()) != null) {
            Object key = row.get(rightKeyIndex);
            buildTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        rightInput.close(); // done with build side
    }

    // For probe phase, we need to handle 1:N matches (one left row may match multiple right rows)
    private Queue<Row> pendingOutput = new LinkedList<>();

    @Override
    public Row next() {
        // If we have pending joined rows from a previous probe, return them first
        if (!pendingOutput.isEmpty()) {
            return pendingOutput.poll();
        }

        // Probe phase: pull left rows until we find a match
        int leftKeyIndex = findColumnIndex(leftSchema, leftColumn);
        Row leftRow;
        while ((leftRow = leftInput.next()) != null) {
            Object key = leftRow.get(leftKeyIndex);
            List<Row> matches = buildTable.get(key);

            if (matches != null) {
                // Found match(es) — combine left + right into output rows
                for (Row rightRow : matches) {
                    Row combined = combineRows(leftRow, rightRow);
                    pendingOutput.add(combined);
                }
                return pendingOutput.poll();
            }
            // No match — skip this left row (inner join behavior)
        }

        return null; // no more left rows
    }

    @Override
    public void close() {
        leftInput.close();
        buildTable = null;
    }

    private Row combineRows(Row left, Row right) {
        Object[] combined = new Object[left.columnCount() + right.columnCount()];
        for (int i = 0; i < left.columnCount(); i++) {
            combined[i] = left.get(i);
        }
        for (int i = 0; i < right.columnCount(); i++) {
            combined[left.columnCount() + i] = right.get(i);
        }
        return new Row(combined);
    }

    private int findColumnIndex(Schema schema, String name) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.column(i).name().equals(name)) return i;
        }
        throw new RuntimeException("Column not found: " + name);
    }
}
