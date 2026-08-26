package com.vksql.storage.writer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.vksql.storage.format.ColumnChunkMetadata;
import com.vksql.storage.format.ColumnDescriptor;
import com.vksql.storage.format.DataType;
import com.vksql.storage.format.FileFooter;
import com.vksql.storage.format.RowGroupMetadata;
import com.vksql.storage.format.Schema;

public class FileFooterDeSerializer {

    private final byte[] fileBytes;

    public FileFooterDeSerializer(byte[] fileBytes) {
        this.fileBytes = fileBytes;
    }

    public FileFooter deSerialize() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        DataInputStream dis = new DataInputStream(bais);

        int columnCount = dis.readInt();

        List<ColumnDescriptor> columns = new ArrayList<>();

        for (int i = 0; i < columnCount; i++) {
            columns.add(new ColumnDescriptor(
                    dis.readUTF(),
                    DataType.values()[dis.readInt()],
                    dis.readInt()));
        }

        Schema schema = new Schema(columns);

        int rowCount = dis.readInt();

        List<RowGroupMetadata> rows = new ArrayList<>();

        for (int i = 0; i < rowCount; i++) {
            List<ColumnChunkMetadata> innerColumns = new ArrayList<>();

            long innerRowCount = dis.readLong();
            int colCount = dis.readInt();

            for (int j = 0; j < colCount; j++) {
                innerColumns.add(new ColumnChunkMetadata(
                        dis.readUTF(),
                        DataType.values()[dis.readInt()],
                        dis.readLong(),
                        dis.readLong(),
                        dis.readLong(),
                        dis.readLong(),
                        dis.readLong()));

            }
            rows.add(new RowGroupMetadata(
                    innerRowCount,
                    innerColumns));
        }

        return new FileFooter(schema, rows);
    }
}
