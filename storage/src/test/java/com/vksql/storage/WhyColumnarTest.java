package com.vksql.storage;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test proves WHY columnar storage is better for analytics.
 * No vksql code needed — just raw byte arrays showing the difference.
 *
 * Scenario: 1 million rows, 10 columns (all int32 = 4 bytes each).
 * Query: SUM(column_3) — we only need 1 column out of 10.
 */
class WhyColumnarTest {

    static final int NUM_ROWS = 1_000_000;
    static final int NUM_COLUMNS = 10;
    static final int INT_SIZE = 4; // bytes per int

    /**
     * ROW STORAGE: [row0_col0, row0_col1, ..., row0_col9, row1_col0, row1_col1, ...]
     *
     * To read column 3, you must skip over columns 0,1,2,4,5,6,7,8,9 for EVERY row.
     * You end up touching the entire byte array.
     */
    @Test
    void rowStorage_readsEverythingToSumOneColumn() {
        // Layout: all rows packed sequentially
        // Total size: 1M rows * 10 cols * 4 bytes = 40MB
        int totalBytes = NUM_ROWS * NUM_COLUMNS * INT_SIZE;
        ByteBuffer rowStore = ByteBuffer.allocate(totalBytes);

        // Write rows
        for (int row = 0; row < NUM_ROWS; row++) {
            for (int col = 0; col < NUM_COLUMNS; col++) {
                rowStore.putInt(row + col); // dummy value
            }
        }

        // Now SUM column 3 — we have to jump through the entire buffer
        long sum = 0;
        int bytesRead = 0;

        for (int row = 0; row < NUM_ROWS; row++) {
            // To get column 3 of this row, offset = (row * 10 + 3) * 4
            int offset = (row * NUM_COLUMNS + 3) * INT_SIZE;
            sum += rowStore.getInt(offset);
            bytesRead += INT_SIZE;
        }

        // We only read 4MB of useful data (column 3)
        // BUT we needed the full 40MB buffer in memory because data is interleaved
        // In real I/O: disk reads in blocks (4KB pages), so you'd read almost everything
        assertEquals(4_000_000, bytesRead, "Bytes we actually needed");
        assertEquals(40_000_000, totalBytes, "Bytes we had to have in memory");

        // The ratio: we needed 10% of the data but had to load 100%
        double wasteRatio = 1.0 - ((double) bytesRead / totalBytes);
        assertTrue(wasteRatio > 0.89, "90% of bytes loaded were wasted, got: " + wasteRatio);

        System.out.println("=== ROW STORAGE ===");
        System.out.println("Total data size: " + (totalBytes / 1024 / 1024) + " MB");
        System.out.println("Bytes needed for SUM(col3): " + (bytesRead / 1024 / 1024) + " MB");
        System.out.println("Waste: " + String.format("%.1f%%", wasteRatio * 100));
        System.out.println("Sum: " + sum);
    }

    /**
     * COLUMNAR STORAGE: [col0_all_rows][col1_all_rows]...[col9_all_rows]
     *
     * To read column 3, you go directly to the column 3 array.
     * You read ONLY 4MB out of 40MB. The other 36MB stays on disk untouched.
     */
    @Test
    void columnarStorage_readsOnlyTheColumnYouNeed() {
        // Layout: each column is a separate contiguous array
        // Each column: 1M rows * 4 bytes = 4MB
        int columnSize = NUM_ROWS * INT_SIZE;
        int totalBytes = columnSize * NUM_COLUMNS; // still 40MB total

        // Store columns separately (simulating columnar file)
        ByteBuffer[] columns = new ByteBuffer[NUM_COLUMNS];
        for (int col = 0; col < NUM_COLUMNS; col++) {
            columns[col] = ByteBuffer.allocate(columnSize);
            for (int row = 0; row < NUM_ROWS; row++) {
                columns[col].putInt(row + col); // same dummy value as row test
            }
        }

        // SUM column 3 — just read that one buffer, nothing else
        long sum = 0;
        int bytesRead = 0;
        ByteBuffer col3 = columns[3];

        for (int row = 0; row < NUM_ROWS; row++) {
            sum += col3.getInt(row * INT_SIZE);
            bytesRead += INT_SIZE;
        }

        // We read exactly 4MB — the one column we needed
        // The other 9 columns (36MB) were never touched
        assertEquals(4_000_000, bytesRead, "Bytes we actually read");
        assertEquals(40_000_000, totalBytes, "Total data on disk");

        // In real I/O: we only read 4MB from disk. 10x less I/O than row storage.
        double efficiency = (double) bytesRead / totalBytes;
        assertTrue(efficiency < 0.11, "We read only ~10% of total data");

        System.out.println("=== COLUMNAR STORAGE ===");
        System.out.println("Total data size: " + (totalBytes / 1024 / 1024) + " MB");
        System.out.println("Bytes read for SUM(col3): " + (bytesRead / 1024 / 1024) + " MB");
        System.out.println("Efficiency: only read " + String.format("%.1f%%", efficiency * 100) + " of data");
        System.out.println("Sum: " + sum);
    }

    /**
     * BONUS: Columnar is also better for compression.
     * Same-type values together have more patterns.
     *
     * Column of timestamps: [1000, 1001, 1002, 1003, ...] → delta encode to [1000, 1, 1, 1, ...]
     * Row storage: [1000, "alice", 99.5, 1001, "bob", 23.1, ...] → no pattern, bad compression
     */
    @Test
    void columnarCompressesBetter() {
        // Simulate a "status" column with only 3 distinct values (low cardinality)
        // In columnar: [0, 1, 2, 0, 0, 1, 2, 0, ...] → dictionary encode to just indices
        int[] statusColumn = new int[NUM_ROWS];
        for (int i = 0; i < NUM_ROWS; i++) {
            statusColumn[i] = i % 3; // only 3 distinct values: 0, 1, 2
        }

        // Raw size: 1M * 4 bytes = 4MB
        int rawSize = NUM_ROWS * INT_SIZE;

        // With dictionary encoding: dictionary = {0, 1, 2} (12 bytes)
        // + indices as 2-bit values: 1M * 2 bits = 250KB
        int dictSize = 3 * INT_SIZE; // 12 bytes for dictionary
        int indexBits = 2; // need 2 bits to represent 0,1,2
        int indicesSize = (NUM_ROWS * indexBits + 7) / 8; // bits to bytes

        double compressionRatio = (double) rawSize / (dictSize + indicesSize);

        assertTrue(compressionRatio > 10, "Dictionary encoding gives >10x compression for low-cardinality data");

        System.out.println("=== COMPRESSION BENEFIT ===");
        System.out.println("Raw column size: " + (rawSize / 1024) + " KB");
        System.out.println("Dictionary encoded size: " + ((dictSize + indicesSize) / 1024) + " KB");
        System.out.println("Compression ratio: " + String.format("%.1fx", compressionRatio));
    }
}
