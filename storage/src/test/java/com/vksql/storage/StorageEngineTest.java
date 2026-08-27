package com.vksql.storage;

import com.vksql.storage.format.*;
import com.vksql.storage.writer.VksqlFileWriter;
import com.vksql.storage.reader.VksqlFileReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: These tests define what your storage engine SHOULD do.
 * The writer is done. Now build the reader (VksqlFileReader + FileFooterDeserializer)
 * to make these pass.
 */
class StorageEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void writeSmallFile_readFooter() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("timestamp", DataType.INT64, 1),
            new ColumnDescriptor("price", DataType.FLOAT64, 2)
        ));

        Path filePath = tempDir.resolve("test.vkql");

        // Write 100 rows
        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < 100; i++) {
                writer.writeRow(i, (long) i * 1000, i * 1.5);
            }
        }

        // Read footer
        var reader = new VksqlFileReader(filePath);
        FileFooter footer = reader.getFooter();

        // Verify schema
        assertEquals(3, footer.schema().columnCount());
        assertEquals("id", footer.schema().column(0).name());
        assertEquals(DataType.INT32, footer.schema().column(0).type());
        assertEquals("timestamp", footer.schema().column(1).name());
        assertEquals(DataType.INT64, footer.schema().column(1).type());
        assertEquals("price", footer.schema().column(2).name());
        assertEquals(DataType.FLOAT64, footer.schema().column(2).type());

        // Verify row group
        assertEquals(1, footer.rows().size()); // 100 rows < 1M, so one row group
        assertEquals(100, footer.rows().get(0).rowCount());
    }

    @Test
    void writeFile_tracksMinMaxStats() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("value", DataType.INT64, 1)
        ));

        Path filePath = tempDir.resolve("stats.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 100; i < 200; i++) {
                writer.writeRow(i, (long) i * 10);
            }
        }

        var reader = new VksqlFileReader(filePath);
        FileFooter footer = reader.getFooter();
        RowGroupMetadata rg = footer.rows().get(0);

        // Column "id": min=100, max=199
        assertEquals(100, rg.columns().get(0).min());
        assertEquals(199, rg.columns().get(0).max());

        // Column "value": min=1000, max=1990
        assertEquals(1000, rg.columns().get(1).min());
        assertEquals(1990, rg.columns().get(1).max());
    }

    @Test
    void writeLargeFile_createsMultipleRowGroups() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0)
        ));

        Path filePath = tempDir.resolve("large.vkql");

        // Write 2.5M rows → should produce 3 row groups (1M + 1M + 500K)
        int totalRows = 2_500_000;
        try (var writer = new VksqlFileWriter(filePath, schema)) {
            for (int i = 0; i < totalRows; i++) {
                writer.writeRow(i);
            }
        }

        var reader = new VksqlFileReader(filePath);
        FileFooter footer = reader.getFooter();

        assertEquals(3, footer.rows().size());
        assertEquals(1_000_000, footer.rows().get(0).rowCount());
        assertEquals(1_000_000, footer.rows().get(1).rowCount());
        assertEquals(500_000, footer.rows().get(2).rowCount());

        // Stats for first row group: id 0..999999
        assertEquals(0, footer.rows().get(0).columns().get(0).min());
        assertEquals(999_999, footer.rows().get(0).columns().get(0).max());

        // Stats for second row group: id 1000000..1999999
        assertEquals(1_000_000, footer.rows().get(1).columns().get(0).min());
        assertEquals(1_999_999, footer.rows().get(1).columns().get(0).max());
    }

    @Test
    void writeAndReadStrings() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("name", DataType.STRING, 1)
        ));

        Path filePath = tempDir.resolve("strings.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow(1, "alice");
            writer.writeRow(2, "bob");
            writer.writeRow(3, "charlie");
        }

        var reader = new VksqlFileReader(filePath);
        FileFooter footer = reader.getFooter();

        // Verify schema
        assertEquals(2, footer.schema().columnCount());
        assertEquals("name", footer.schema().column(1).name());
        assertEquals(DataType.STRING, footer.schema().column(1).type());

        // Verify row group
        assertEquals(1, footer.rows().size());
        assertEquals(3, footer.rows().get(0).rowCount());

        // Verify id stats
        assertEquals(1, footer.rows().get(0).columns().get(0).min());
        assertEquals(3, footer.rows().get(0).columns().get(0).max());
    }

    @Test
    void writeAndReadNulls() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("name", DataType.STRING, 1),
            new ColumnDescriptor("score", DataType.FLOAT64, 2)
        ));

        Path filePath = tempDir.resolve("nulls.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow(1, "alice", 95.5);
            writer.writeRow(2, null, 87.0);       // null string
            writer.writeRow(3, "charlie", null);   // null double
            writer.writeRow(4, null, null);        // both null
        }

        var reader = new VksqlFileReader(filePath);
        FileFooter footer = reader.getFooter();

        assertEquals(1, footer.rows().size());
        assertEquals(4, footer.rows().get(0).rowCount());

        // id column: no nulls, min=1, max=4
        assertEquals(1, footer.rows().get(0).columns().get(0).min());
        assertEquals(4, footer.rows().get(0).columns().get(0).max());
    }

    @Test
    void readColumnData_fixedTypes() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0),
            new ColumnDescriptor("value", DataType.INT64, 1)
        ));

        Path filePath = tempDir.resolve("readback.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow(10, 100L);
            writer.writeRow(20, 200L);
            writer.writeRow(30, 300L);
        }

        // Read footer to get offsets
        var fileReader = new VksqlFileReader(filePath);
        FileFooter footer = fileReader.getFooter();
        RowGroupMetadata rg = footer.rows().get(0);

        // Read column "id" data
        var raf = new java.io.RandomAccessFile(filePath.toFile(), "r");
        var colReader = new com.vksql.storage.reader.ColumnReader(raf, rg.columns().get(0), DataType.INT32);
        var colData = colReader.read();

        assertEquals(3, colData.valueCount());
        assertEquals(10, colData.values()[0]);
        assertEquals(20, colData.values()[1]);
        assertEquals(30, colData.values()[2]);

        // Read column "value" data
        var colReader2 = new com.vksql.storage.reader.ColumnReader(raf, rg.columns().get(1), DataType.INT64);
        var colData2 = colReader2.read();

        assertEquals(3, colData2.valueCount());
        assertEquals(100L, colData2.values()[0]);
        assertEquals(200L, colData2.values()[1]);
        assertEquals(300L, colData2.values()[2]);

        raf.close();
    }

    @Test
    void readColumnData_withNulls() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("id", DataType.INT32, 0)
        ));

        Path filePath = tempDir.resolve("readnulls.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow(10);
            writer.writeRow((Object) null);
            writer.writeRow(30);
            writer.writeRow((Object) null);
            writer.writeRow(50);
        }

        var fileReader = new VksqlFileReader(filePath);
        FileFooter footer = fileReader.getFooter();
        RowGroupMetadata rg = footer.rows().get(0);

        var raf = new java.io.RandomAccessFile(filePath.toFile(), "r");
        var colReader = new com.vksql.storage.reader.ColumnReader(raf, rg.columns().get(0), DataType.INT32);
        var colData = colReader.read();

        assertEquals(5, colData.valueCount());
        assertEquals(10, colData.values()[0]);
        assertNull(colData.values()[1]);
        assertEquals(30, colData.values()[2]);
        assertNull(colData.values()[3]);
        assertEquals(50, colData.values()[4]);

        raf.close();
    }

    @Test
    void readColumnData_strings() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("name", DataType.STRING, 0)
        ));

        Path filePath = tempDir.resolve("readstrings.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow("alice");
            writer.writeRow((Object) null);
            writer.writeRow("charlie");
            writer.writeRow("bob");
        }

        var fileReader = new VksqlFileReader(filePath);
        FileFooter footer = fileReader.getFooter();
        RowGroupMetadata rg = footer.rows().get(0);

        var raf = new java.io.RandomAccessFile(filePath.toFile(), "r");
        var colReader = new com.vksql.storage.reader.ColumnReader(raf, rg.columns().get(0), DataType.STRING);
        var colData = colReader.read();

        assertEquals(4, colData.valueCount());
        assertEquals("alice", colData.values()[0]);
        assertNull(colData.values()[1]);
        assertEquals("charlie", colData.values()[2]);
        assertEquals("bob", colData.values()[3]);

        raf.close();
    }

    @Test
    void fileBeginsAndEndsWithMagicNumber() throws Exception {
        Schema schema = new Schema(List.of(
            new ColumnDescriptor("x", DataType.INT32, 0)
        ));

        Path filePath = tempDir.resolve("magic.vkql");

        try (var writer = new VksqlFileWriter(filePath, schema)) {
            writer.writeRow(42);
        }

        // Verify magic bytes at start and end
        try (var raf = new java.io.RandomAccessFile(filePath.toFile(), "r")) {
            // First 4 bytes
            byte[] startMagic = new byte[4];
            raf.readFully(startMagic);
            assertEquals("VKQL", new String(startMagic));

            // Last 4 bytes
            raf.seek(raf.length() - 4);
            byte[] endMagic = new byte[4];
            raf.readFully(endMagic);
            assertEquals("VKQL", new String(endMagic));
        }
    }
}
