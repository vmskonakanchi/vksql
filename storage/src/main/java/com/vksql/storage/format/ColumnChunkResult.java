package com.vksql.storage.format;

import java.util.List;

public record ColumnChunkResult(ColumnDescriptor descriptor, List<Page> pages, long minValue, long maxValue,
        long totalValueCount) {

}
