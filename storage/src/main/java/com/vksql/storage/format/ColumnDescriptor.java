package com.vksql.storage.format;

public record ColumnDescriptor(String name, DataType type, int columnIndex) {
}
