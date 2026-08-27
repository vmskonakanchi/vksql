package com.vksql.storage.format;

import com.vksql.storage.writer.NullBitMap;

public record ColumnData(Object[] values, NullBitMap nullBitMap, int valueCount) {
}
