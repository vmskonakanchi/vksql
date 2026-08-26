package com.vksql.storage.writer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.vksql.storage.format.ColumnChunkMetadata;
import com.vksql.storage.format.ColumnChunkResult;
import com.vksql.storage.format.FileFooter;
import com.vksql.storage.format.Page;
import com.vksql.storage.format.RowGroupMetadata;
import com.vksql.storage.format.Schema;

public class VksqlFileWriter implements Closeable {

    private static final byte[] VKQL_BYTES = "VKQL".getBytes();

    private RowGroupWriter currenGroupWriter;
    private final DataOutputStream dos;
    private long bytesWritten;
    private final Schema schema;
    private final List<RowGroupMetadata> rListMetadatas = new ArrayList<>();

    public VksqlFileWriter(Path filePath, Schema schema) throws IOException {
        this.dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)));
        this.schema = schema;
        dos.write(VKQL_BYTES);
        this.bytesWritten += VKQL_BYTES.length;
        this.currenGroupWriter = new RowGroupWriter(schema);
    }

    public void writeRow(Object... values) throws IOException {
        if (currenGroupWriter.isFull()) {
            flushRowGroup();
            currenGroupWriter = new RowGroupWriter(this.schema);
        }
        currenGroupWriter.writeRow(values);
    }

    private void flushRowGroup() throws IOException {
        List<ColumnChunkMetadata> cListMetadatas = new ArrayList<>();
        List<ColumnChunkResult> ccr = currenGroupWriter.finish();

        for (ColumnChunkResult c : ccr) {
            long offset = bytesWritten;

            for (Page p : c.pages()) {
                dos.write(p.data());
                bytesWritten += p.data().length;
            }

            cListMetadatas.add(new ColumnChunkMetadata(
                    c.descriptor().name(),
                    c.descriptor().type(),
                    offset, bytesWritten - offset,
                    c.totalValueCount(),
                    c.minValue(),
                    c.maxValue()));
        }

        rListMetadatas.add(new RowGroupMetadata(
                currenGroupWriter.getCurrentCount(),
                cListMetadatas));
    }

    @Override
    public void close() throws IOException {
        flushRowGroup();
        byte[] footerBytes = new FileFooterSerializer(
                new FileFooter(
                        schema,
                        rListMetadatas))
                .serialize();
        dos.write(footerBytes);
        dos.writeInt(footerBytes.length);
        dos.write(VKQL_BYTES);
        dos.close();
    }

}
