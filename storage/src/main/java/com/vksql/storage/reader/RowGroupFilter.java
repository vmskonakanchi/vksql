package com.vksql.storage.reader;

import com.vksql.storage.format.ColumnChunkMetadata;
import com.vksql.storage.format.RowGroupMetadata;

/**
 * Predicate pushdown: skip entire row groups using min/max statistics.
 *
 * If a row group's max(price) = 200 and the query is WHERE price > 250,
 * we KNOW no rows in that group can match. Skip it entirely — zero I/O.
 *
 * This is how Parquet/DuckDB achieve "reading 10% of data for selective queries."
 */
public class RowGroupFilter {

    /**
     * Returns true if this row group MIGHT contain matching rows.
     * Returns false if we can GUARANTEE no rows match (safe to skip).
     */
    public static boolean mightMatch(RowGroupMetadata rg, String columnName, String operator, long value) {
        // Find the column's stats
        for (ColumnChunkMetadata col : rg.columns()) {
            if (col.name().equals(columnName)) {
                long min = col.min();
                long max = col.max();

                return switch (operator) {
                    // WHERE col > value: skip if max <= value (all values are <= value)
                    case ">" -> max > value;
                    // WHERE col >= value: skip if max < value
                    case ">=" -> max >= value;
                    // WHERE col < value: skip if min >= value (all values are >= value)
                    case "<" -> min < value;
                    // WHERE col <= value: skip if min > value
                    case "<=" -> min <= value;
                    // WHERE col = value: skip if value < min or value > max
                    case "=" -> value >= min && value <= max;
                    // WHERE col != value: can only skip if min == max == value (all same value)
                    case "!=", "<>" -> !(min == value && max == value);
                    default -> true; // unknown operator, don't skip
                };
            }
        }
        return true; // column not found in stats, don't skip
    }
}
