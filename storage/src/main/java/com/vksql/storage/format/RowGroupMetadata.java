package com.vksql.storage.format;

import java.util.List;

public record RowGroupMetadata(long rowCount, List<ColumnChunkMetadata> columns) {

    public int columnCount() {
        return columns.size();
    }
}
