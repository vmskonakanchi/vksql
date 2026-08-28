package com.vksql.network.worker;

import com.vksql.network.proto.*;
import io.grpc.stub.StreamObserver;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * gRPC implementation of the WorkerService.
 *
 * Handles task execution requests from the coordinator and heartbeat checks.
 */
public class WorkerServiceImpl extends WorkerServiceGrpc.WorkerServiceImplBase {

    private final Worker worker;

    public WorkerServiceImpl(Worker worker) {
        this.worker = worker;
    }

    @Override
    public void executeTask(TaskRequest request, StreamObserver<TaskResult> responseObserver) {
        long startTime = System.currentTimeMillis();

        try {
            // Execute the task locally using the Worker's execution engine
            Map<Object, Long> results = worker.executeTask(
                    request.getTableName(),
                    request.getFilterColumn(),
                    request.getFilterOperator(),
                    request.getFilterValue(),
                    resolveGroupByColIndex(request.getGroupByColumn()),
                    resolveAggColIndex(request.getAggregateColumn()),
                    request.getAggregateFunction()
            );

            // Build the result proto with group keys and aggregated values
            RecordBatch.Builder batchBuilder = RecordBatch.newBuilder();
            batchBuilder.setNumRows(results.size());

            if (!results.isEmpty()) {
                // Key column (INT32 — group by nation which is int)
                ByteBuffer keyBuffer = ByteBuffer.allocate(results.size() * 4);
                // Value column (INT64 — aggregated sums)
                ByteBuffer valueBuffer = ByteBuffer.allocate(results.size() * 8);

                for (Map.Entry<Object, Long> entry : results.entrySet()) {
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
                        .setNumValues(results.size())
                        .build());

                batchBuilder.addColumns(ColumnData.newBuilder()
                        .setType(ColumnData.DataType.INT64)
                        .setValues(com.google.protobuf.ByteString.copyFrom(valueBuffer))
                        .setNumValues(results.size())
                        .build());
            }

            long executionTime = System.currentTimeMillis() - startTime;

            TaskResult result = TaskResult.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus(TaskStatus.SUCCESS)
                    .setResult(batchBuilder.build())
                    .setRowsProcessed(results.size())
                    .setExecutionTimeMs(executionTime)
                    .build();

            responseObserver.onNext(result);
            responseObserver.onCompleted();

        } catch (Exception e) {
            TaskResult errorResult = TaskResult.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus(TaskStatus.FAILED)
                    .setExecutionTimeMs(System.currentTimeMillis() - startTime)
                    .build();

            responseObserver.onNext(errorResult);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        HeartbeatResponse response = HeartbeatResponse.newBuilder()
                .setAcknowledged(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Resolve group-by column name to index in schema (id=0, price=1, nation=2).
     * This uses a simple convention matching the test schema.
     */
    private int resolveGroupByColIndex(String columnName) {
        return switch (columnName.toLowerCase()) {
            case "id" -> 0;
            case "price" -> 1;
            case "nation" -> 2;
            default -> throw new IllegalArgumentException("Unknown column: " + columnName);
        };
    }

    /**
     * Resolve aggregate column name to index in schema.
     */
    private int resolveAggColIndex(String columnName) {
        return switch (columnName.toLowerCase()) {
            case "id" -> 0;
            case "price" -> 1;
            case "nation" -> 2;
            default -> throw new IllegalArgumentException("Unknown column: " + columnName);
        };
    }
}
