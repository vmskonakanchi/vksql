package com.vksql.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts all input rows by the given sort keys.
 * Materializes the entire input in open(), then returns rows one-by-one in next().
 */
public class SortOperator implements Operator {
    private final Operator input;
    private final List<SortKey> sortKeys;
    private List<Row> sortedRows;
    private int position;

    public SortOperator(Operator input, List<SortKey> sortKeys) {
        this.input = input;
        this.sortKeys = sortKeys;
    }

    @Override
    public void open() {
        input.open();

        // Pull all rows from input
        sortedRows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            sortedRows.add(row);
        }

        // Build comparator from sort keys
        Comparator<Row> comparator = buildComparator();
        sortedRows.sort(comparator);

        position = 0;
    }

    @Override
    public Row next() {
        if (position >= sortedRows.size()) {
            return null;
        }
        return sortedRows.get(position++);
    }

    @Override
    public void close() {
        input.close();
        sortedRows = null;
    }

    @SuppressWarnings("unchecked")
    private Comparator<Row> buildComparator() {
        Comparator<Row> comparator = null;

        for (SortKey key : sortKeys) {
            Comparator<Row> keyComparator = (r1, r2) -> {
                Comparable<Object> v1 = (Comparable<Object>) r1.get(key.columnIndex());
                Comparable<Object> v2 = (Comparable<Object>) r2.get(key.columnIndex());

                if (v1 == null && v2 == null) return 0;
                if (v1 == null) return key.ascending() ? -1 : 1;
                if (v2 == null) return key.ascending() ? 1 : -1;

                int cmp = v1.compareTo(v2);
                return key.ascending() ? cmp : -cmp;
            };

            comparator = (comparator == null) ? keyComparator : comparator.thenComparing(keyComparator);
        }

        return comparator;
    }
}
