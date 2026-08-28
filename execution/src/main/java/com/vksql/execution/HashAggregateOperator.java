package com.vksql.execution;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.Schema;

import java.util.*;

public class HashAggregateOperator implements Operator {
    private final Operator input;
    private final Schema schema;
    private final List<Expr> groupByExprs;       // e.g., [ColumnRef("nation")]
    private final List<FunctionCall> aggregates;  // e.g., [sum(price), count(*)]

    private Map<Object, long[]> map;             // key → aggregate values
    private Iterator<Map.Entry<Object, long[]>> iterator;

    public HashAggregateOperator(Operator input, Schema schema,
                                  List<Expr> groupByExprs, List<FunctionCall> aggregates) {
        this.input = input;
        this.schema = schema;
        this.groupByExprs = groupByExprs;
        this.aggregates = aggregates;
    }

    @Override
    public void open() {
        input.open();
        map = new HashMap<>();

        // Pull ALL rows from input and build the map
        Row row;
        while ((row = input.next()) != null) {
            // 1. Get the group-by key
            Object key = getGroupKey(row);

            // 2. Look up or create entry
            long[] aggs = map.computeIfAbsent(key, k -> new long[aggregates.size()]);

            // 3. Update each aggregate
            for (int i = 0; i < aggregates.size(); i++) {
                FunctionCall func = aggregates.get(i);
                String funcName = func.name().toLowerCase();

                switch (funcName) {
                    case "sum" -> {
                        // Get the column value being summed
                        Object val = resolveValue(func.args().get(0), row);
                        if (val != null) {
                            aggs[i] += toNumber(val);
                        }
                    }
                    case "count" -> {
                        aggs[i]++;
                    }
                    case "min" -> {
                        Object val = resolveValue(func.args().get(0), row);
                        if (val != null) {
                            long v = toNumber(val);
                            if (aggs[i] == 0 && !map.containsKey(key)) {
                                aggs[i] = v; // first value
                            } else {
                                aggs[i] = Math.min(aggs[i], v);
                            }
                        }
                    }
                    case "max" -> {
                        Object val = resolveValue(func.args().get(0), row);
                        if (val != null) {
                            long v = toNumber(val);
                            aggs[i] = Math.max(aggs[i], v);
                        }
                    }
                }
            }
        }

        iterator = map.entrySet().iterator();
    }

    @Override
    public Row next() {
        if (!iterator.hasNext()) return null;

        Map.Entry<Object, long[]> entry = iterator.next();
        // Build output row: [groupKey, agg0, agg1, ...]
        Object[] values = new Object[1 + aggregates.size()];
        values[0] = entry.getKey();
        for (int i = 0; i < aggregates.size(); i++) {
            values[i + 1] = entry.getValue()[i];
        }
        return new Row(values);
    }

    @Override
    public void close() {
        input.close();
        map = null;
    }

    private Object getGroupKey(Row row) {
        // For now, support single column group-by
        Expr groupExpr = groupByExprs.get(0);
        return resolveValue(groupExpr, row);
    }

    private Object resolveValue(Expr expr, Row row) {
        if (expr instanceof ColumnRef col) {
            int index = findColumnIndex(col.name());
            return row.get(index);
        }
        if (expr instanceof IntLiteral lit) return lit.value();
        return null;
    }

    private long toNumber(Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0;
    }

    private int findColumnIndex(String name) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.column(i).name().equals(name)) return i;
        }
        throw new RuntimeException("Column not found: " + name);
    }
}
