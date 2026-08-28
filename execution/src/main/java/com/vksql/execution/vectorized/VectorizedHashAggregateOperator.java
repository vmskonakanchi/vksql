package com.vksql.execution.vectorized;

import com.vksql.parser.expr.*;
import com.vksql.storage.format.Schema;

import java.util.*;

/**
 * Vectorized hash aggregate: processes batches instead of single rows.
 * Still uses a HashMap, but feeds it from arrays in tight loops.
 */
public class VectorizedHashAggregateOperator implements VectorizedOperator {
    private final VectorizedOperator input;
    private final Schema schema;
    private final int groupByColumnIndex;
    private final int aggregateColumnIndex;
    private final String aggregateFunction;

    private Map<Object, long[]> map;
    private Iterator<Map.Entry<Object, long[]>> iterator;

    public VectorizedHashAggregateOperator(VectorizedOperator input, Schema schema,
                                            int groupByColumnIndex, int aggregateColumnIndex,
                                            String aggregateFunction) {
        this.input = input;
        this.schema = schema;
        this.groupByColumnIndex = groupByColumnIndex;
        this.aggregateColumnIndex = aggregateColumnIndex;
        this.aggregateFunction = aggregateFunction;
    }

    @Override
    public void open() {
        input.open();
        map = new HashMap<>();

        // Consume all batches
        RecordBatch batch;
        while ((batch = input.next()) != null) {
            processBatch(batch);
        }

        iterator = map.entrySet().iterator();
    }

    private void processBatch(RecordBatch batch) {
        int rowCount = batch.rowCount();
        Object groupCol = batch.getColumn(groupByColumnIndex);
        Object valueCol = batch.getColumn(aggregateColumnIndex);

        // Tight loop over the batch
        if (valueCol instanceof long[] values) {
            if (groupCol instanceof int[] keys) {
                for (int i = 0; i < rowCount; i++) {
                    long[] agg = map.computeIfAbsent(keys[i], k -> new long[1]);
                    agg[0] += values[i]; // sum
                }
            } else if (groupCol instanceof String[] keys) {
                for (int i = 0; i < rowCount; i++) {
                    long[] agg = map.computeIfAbsent(keys[i], k -> new long[1]);
                    agg[0] += values[i];
                }
            }
        } else if (valueCol instanceof int[] values) {
            if (groupCol instanceof int[] keys) {
                for (int i = 0; i < rowCount; i++) {
                    long[] agg = map.computeIfAbsent(keys[i], k -> new long[1]);
                    agg[0] += values[i];
                }
            }
        }
    }

    @Override
    public RecordBatch next() {
        // Return results one batch at a time (all results in one batch for simplicity)
        if (iterator == null || !iterator.hasNext()) return null;

        // Collect all results into arrays
        List<Map.Entry<Object, long[]>> entries = new ArrayList<>();
        while (iterator.hasNext()) {
            entries.add(iterator.next());
        }

        int size = entries.size();
        Object[] groupKeys = new Object[size];
        long[] sums = new long[size];

        for (int i = 0; i < size; i++) {
            groupKeys[i] = entries.get(i).getKey();
            sums[i] = entries.get(i).getValue()[0];
        }

        // Determine key column type
        Object keyColumn;
        if (groupKeys[0] instanceof Integer) {
            int[] intKeys = new int[size];
            for (int i = 0; i < size; i++) intKeys[i] = (int) groupKeys[i];
            keyColumn = intKeys;
        } else {
            String[] strKeys = new String[size];
            for (int i = 0; i < size; i++) strKeys[i] = (String) groupKeys[i];
            keyColumn = strKeys;
        }

        return new RecordBatch(new Object[]{keyColumn, sums}, size);
    }

    @Override
    public void close() {
        input.close();
        map = null;
    }
}
