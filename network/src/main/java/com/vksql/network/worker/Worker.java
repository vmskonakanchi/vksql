package com.vksql.network.worker;

import com.vksql.execution.vectorized.*;
import com.vksql.parser.expr.*;
import com.vksql.storage.format.*;
import com.vksql.storage.reader.MappedFileReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Worker: executes tasks assigned by the coordinator.
 *
 * Responsibilities:
 * 1. Register with coordinator (send heartbeats)
 * 2. Receive TaskRequest from coordinator
 * 3. Execute locally (scan local data, filter, aggregate)
 * 4. Return TaskResult (or shuffle to other workers)
 *
 * Each worker owns a partition of the data (subset of .vkql files).
 */
public class Worker {

    private final String workerId;
    private final Path dataDirectory;  // where this worker's .vkql files live
    private final int port;
    private final String coordinatorAddress;

    public Worker(String workerId, Path dataDirectory, int port, String coordinatorAddress) {
        this.workerId = workerId;
        this.dataDirectory = dataDirectory;
        this.port = port;
        this.coordinatorAddress = coordinatorAddress;
    }

    /**
     * Start the worker. Currently a no-op — gRPC server will be added later.
     */
    public void start() {
        // TODO: Start gRPC server for receiving tasks
        // TODO: Start heartbeat thread (send heartbeat to coordinator every 5s)
    }

    /**
     * Execute a task locally using the vectorized execution engine.
     * Builds an operator chain: Scan → Filter → HashAggregate.
     *
     * @param tableName      name of the table (maps to dataDirectory/tableName.vkql)
     * @param filterColumn   column name to filter on
     * @param filterOp       comparison operator (">", "<", ">=", "<=", "=", "!=")
     * @param filterValue    value to compare against
     * @param groupByColIndex column index to group by
     * @param aggColIndex    column index to aggregate
     * @param aggFunction    aggregate function name ("sum", "count", etc.)
     * @return Map of groupKey → aggregated value
     */
    public Map<Object, Long> executeTask(String tableName, String filterColumn, String filterOp,
                                          long filterValue, int groupByColIndex, int aggColIndex,
                                          String aggFunction) {
        Path filePath = dataDirectory.resolve(tableName + ".vkql");

        // 1. Read schema from file footer
        Schema schema;
        MappedFileReader footerReader = null;
        try {
            footerReader = new MappedFileReader(filePath);
            schema = footerReader.getFooter().schema();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read schema from: " + filePath, e);
        } finally {
            if (footerReader != null) footerReader.close();
        }

        // 2. Build operator chain: Scan → Filter → Aggregate
        MappedVectorizedScanOperator scan = new MappedVectorizedScanOperator(filePath, schema);

        ComparisonExpr condition = new ComparisonExpr(
                new ColumnRef(filterColumn),
                filterOp,
                new IntLiteral(filterValue)
        );
        VectorizedFilterOperator filter = new VectorizedFilterOperator(scan, condition, schema);

        VectorizedHashAggregateOperator aggregate = new VectorizedHashAggregateOperator(
                filter, schema, groupByColIndex, aggColIndex, aggFunction
        );

        // 3. Execute and collect all result batches
        Map<Object, Long> results = new HashMap<>();
        aggregate.open();
        try {
            RecordBatch batch;
            while ((batch = aggregate.next()) != null) {
                int rowCount = batch.rowCount();
                Object keyColumn = batch.getColumn(0);
                long[] valueColumn = batch.getLongColumn(1);

                if (keyColumn instanceof int[] intKeys) {
                    for (int i = 0; i < rowCount; i++) {
                        results.put(intKeys[i], valueColumn[i]);
                    }
                } else if (keyColumn instanceof long[] longKeys) {
                    for (int i = 0; i < rowCount; i++) {
                        results.put(longKeys[i], valueColumn[i]);
                    }
                } else if (keyColumn instanceof String[] strKeys) {
                    for (int i = 0; i < rowCount; i++) {
                        results.put(strKeys[i], valueColumn[i]);
                    }
                }
            }
        } finally {
            aggregate.close();
        }

        return results;
    }

    public String getWorkerId() { return workerId; }
    public Path getDataDirectory() { return dataDirectory; }
}
