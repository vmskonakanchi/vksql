package com.vksql.storage.format;

import java.util.List;

public record Schema(List<ColumnDescriptor> columns) {
    public int columnCount() {
        return columns.size();
    }

    public ColumnDescriptor column(int index) {
        return columns.get(index);
    }
}
