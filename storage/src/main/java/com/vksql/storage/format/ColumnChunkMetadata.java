package com.vksql.storage.format;

public record ColumnChunkMetadata(String name, DataType type, long fileOffSet, long totalSize, long numValues, long min,
        long max) {

}
