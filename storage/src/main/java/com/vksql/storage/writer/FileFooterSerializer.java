package com.vksql.storage.writer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import com.vksql.storage.format.FileFooter;

public class FileFooterSerializer {

    private final FileFooter fileFooter;

    public FileFooterSerializer(FileFooter fileFooter) {
        this.fileFooter = fileFooter;
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(fileFooter.schema().columnCount());

        for (var col : fileFooter.schema().columns()) {
            dos.writeUTF(col.name());
            dos.writeInt(col.type().ordinal());
            dos.writeInt(col.columnIndex());
        }

        dos.writeInt(fileFooter.rowCount());

        for (var row : fileFooter.rows()) {
            dos.writeLong(row.rowCount());
            dos.writeInt(row.columnCount());

            for (var col : row.columns()) {
                dos.writeUTF(col.name());
                dos.writeInt(col.type().ordinal());
                dos.writeLong(col.fileOffSet());
                dos.writeLong(col.totalSize());
                dos.writeLong(col.numValues());
                dos.writeLong(col.min());
                dos.writeLong(col.max());
            }
        }

        return baos.toByteArray();
    }
}
