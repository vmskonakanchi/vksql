package com.vksql.execution.vectorized;

import com.vksql.storage.format.*;
import com.vksql.storage.reader.MappedFileReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel filter + aggregate directly on memory-mapped arrays.
 * Splits work into chunks across multiple threads.
 *
 * This bypasses the operator model entirely for maximum speed —
 * directly processes the raw column arrays in parallel.
 */
public class ParallelQueryExecutor {

    private final Path filePath;
    private final Schema schema;

    public ParallelQueryExecutor(Path filePath, Schema schema) {
        this.filePath = filePath;
        this.schema = schema;
    }

    /**
     * Execute: SELECT groupByCol, sum(aggCol) WHERE filterCol > filterValue GROUP BY groupByCol
     * Fully parallel — splits rows across available cores.
     */
    public ConcurrentHashMap<Object, AtomicLong> parallelFilterAggregate(
            int filterColIndex, long filterValue,
            int groupByColIndex, int aggColIndex) throws Exception {

        MappedFileReader reader = new MappedFileReader(filePath);
        FileFooter footer = reader.getFooter();
        RowGroupMetadata rg = footer.rows().get(0);
        int totalRows = (int) rg.rowCount();

        // Read columns into typed arrays (one-time cost)
        long[] filterCol = reader.readLongColumnDirect(rg, filterColIndex);
        int[] groupByCol = reader.readIntColumnDirect(rg, groupByColIndex);
        long[] aggCol = reader.readLongColumnDirect(rg, aggColIndex);

        // Shared result map
        ConcurrentHashMap<Object, AtomicLong> result = new ConcurrentHashMap<>();

        // Split work across cores
        int numThreads = Runtime.getRuntime().availableProcessors();
        int chunkSize = (totalRows + numThreads - 1) / numThreads;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                int start = t * chunkSize;
                int end = Math.min(start + chunkSize, totalRows);

                executor.submit(() -> {
                    try {
                        // Each thread processes its chunk — tight loop, no contention
                        // Use thread-local partial aggregates to avoid CAS contention
                        var localMap = new java.util.HashMap<Integer, Long>();

                        for (int i = start; i < end; i++) {
                            if (filterCol[i] > filterValue) {
                                localMap.merge(groupByCol[i], aggCol[i], Long::sum);
                            }
                        }

                        // Merge local results into shared map
                        for (var entry : localMap.entrySet()) {
                            result.computeIfAbsent(entry.getKey(), k -> new AtomicLong())
                                  .addAndGet(entry.getValue());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
        }

        reader.close();
        return result;
    }
}
