package com.vksql.storage.format;

import java.util.List;

public record FileFooter(Schema schema, List<RowGroupMetadata> rows) {
    
    public int rowCount() { 
        return rows.size();
    }
}
