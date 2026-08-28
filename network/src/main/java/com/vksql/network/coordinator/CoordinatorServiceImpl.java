package com.vksql.network.coordinator;

import com.vksql.network.proto.*;
import com.vksql.storage.format.ColumnDescriptor;
import com.vksql.storage.format.DataType;
import com.vksql.storage.format.Schema;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of the CoordinatorService.
 *
 * Handles query requests from clients by:
 * 1. Planning the query into per-worker tasks
 * 2. Distributing tasks to workers via gRPC
 * 3. Collecting and merging partial results
 * 4. Returning the final merged result to the client
 */
public class CoordinatorServiceImpl extends CoordinatorServiceGrpc.CoordinatorServiceImplBase {

    private final Coordinator coordinator;
    private final Schema schema;

    public CoordinatorServiceImpl(Coordinator coordinator, Schema schema) {
        this.coordinator = coordinator;
        this.schema = schema;
    }

    @Override
    public void executeQuery(QueryRequest request, StreamObserver<QueryResult> responseObserver) {
        long startTime = System.currentTimeMillis();

        try {
            String sql = request.getSql();

            // Plan the query into per-worker tasks
            List<TaskAssignment> tasks = coordinator.planDistributed(sql, schema);

            // Get alive workers to determine their network addresses
            List<WorkerRegistry.WorkerInfo> workers = coordinator.getWorkerRegistry().getAliveWorkers();

            // Map workerId → WorkerInfo for address lookup
            Map<String, WorkerRegistry.WorkerInfo> workerInfoMap = new HashMap<>();
            for (WorkerRegistry.WorkerInfo w : workers) {
                workerInfoMap.put(w.id, w);
            }

            // Execute tasks on workers via gRPC and collect partial results
            Map<Object, Long> mergedResults = new ConcurrentHashMap<>();
            List<ManagedChannel> channels = new ArrayList<>();

            try {
                for (TaskAssignment task : tasks) {
                    WorkerRegistry.WorkerInfo workerInfo = workerInfoMap.get(task.workerId());
                    if (workerInfo == null) {
                        throw new IllegalStateException("Worker not found: " + task.workerId());
                    }

                    // Create gRPC channel to worker
                    ManagedChannel channel = ManagedChannelBuilder
                            .forAddress(workerInfo.host, workerInfo.port)
                            .usePlaintext()
                            .build();
                    channels.add(channel);

                    // Build the TaskRequest proto
                    TaskRequest taskRequest = TaskRequest.newBuilder()
                            .setQueryId(request.getQueryId())
                            .setTaskId(task.workerId() + "-task-0")
                            .setStageId(0)
                            .setTableName(task.tableName())
                            .setFilterColumn(task.filterColumn())
                            .setFilterOperator(task.filterOp())
                            .setFilterValue(task.filterValue())
                            .setGroupByColumn(schema.column(task.groupByColIndex()).name())
                            .setAggregateFunction(task.aggFunction())
                            .setAggregateColumn(schema.column(task.aggColIndex()).name())
                            .build();

                    // Send task to worker synchronously (blocking stub)
                    WorkerServiceGrpc.WorkerServiceBlockingStub stub =
                            WorkerServiceGrpc.newBlockingStub(channel);

                    TaskResult taskResult = stub.executeTask(taskRequest);

                    if (taskResult.getStatus() == TaskStatus.SUCCESS) {
                        // Parse the result batch back into Map<Object, Long>
                        Map<Object, Long> partialResult = deserializeResult(taskResult.getResult());

                        // Merge partial results — sum values for each group key
                        for (Map.Entry<Object, Long> entry : partialResult.entrySet()) {
                            mergedResults.merge(entry.getKey(), entry.getValue(), Long::sum);
                        }
                    } else {
                        throw new RuntimeException("Task failed on worker: " + task.workerId());
                    }
                }
            } finally {
                // Shutdown all channels
                for (ManagedChannel channel : channels) {
                    channel.shutdown();
                    try {
                        channel.awaitTermination(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // Build the final QueryResult with merged data
            RecordBatch.Builder batchBuilder = RecordBatch.newBuilder();
            batchBuilder.setNumRows(mergedResults.size());

            if (!mergedResults.isEmpty()) {
                ByteBuffer keyBuffer = ByteBuffer.allocate(mergedResults.size() * 4);
                ByteBuffer valueBuffer = ByteBuffer.allocate(mergedResults.size() * 8);

                for (Map.Entry<Object, Long> entry : mergedResults.entrySet()) {
                    Object key = entry.getKey();
                    if (key instanceof Integer intKey) {
                        keyBuffer.putInt(intKey);
                    } else if (key instanceof Long longKey) {
                        keyBuffer.putInt(longKey.intValue());
                    }
                    valueBuffer.putLong(entry.getValue());
                }

                keyBuffer.flip();
                valueBuffer.flip();

                batchBuilder.addColumns(ColumnData.newBuilder()
                        .setType(ColumnData.DataType.INT32)
                        .setValues(com.google.protobuf.ByteString.copyFrom(keyBuffer))
                        .setNumValues(mergedResults.size())
                        .build());

                batchBuilder.addColumns(ColumnData.newBuilder()
                        .setType(ColumnData.DataType.INT64)
                        .setValues(com.google.protobuf.ByteString.copyFrom(valueBuffer))
                        .setNumValues(mergedResults.size())
                        .build());
            }

            long executionTime = System.currentTimeMillis() - startTime;

            QueryResult result = QueryResult.newBuilder()
                    .addBatches(batchBuilder.build())
                    .setRowsReturned(mergedResults.size())
                    .setExecutionTimeMs(executionTime)
                    .build();

            responseObserver.onNext(result);
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Query execution failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Deserialize a RecordBatch proto back into a Map<Object, Long>.
     * Expects 2 columns: INT32 keys and INT64 values.
     */
    private Map<Object, Long> deserializeResult(RecordBatch batch) {
        Map<Object, Long> result = new HashMap<>();

        if (batch.getColumnsCount() < 2) {
            return result;
        }

        ColumnData keyColumn = batch.getColumns(0);
        ColumnData valueColumn = batch.getColumns(1);

        ByteBuffer keyBuffer = keyColumn.getValues().asReadOnlyByteBuffer();
        ByteBuffer valueBuffer = valueColumn.getValues().asReadOnlyByteBuffer();

        int numValues = keyColumn.getNumValues();
        for (int i = 0; i < numValues; i++) {
            int key = keyBuffer.getInt();
            long value = valueBuffer.getLong();
            result.put(key, value);
        }

        return result;
    }
}
