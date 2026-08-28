package com.vksql.network.coordinator;

/**
 * Represents a task assigned to a specific worker.
 * Contains all the parameters needed for the worker to execute a partial query.
 */
public record TaskAssignment(
        String workerId,
        String tableName,
        String filterColumn,
        String filterOp,
        long filterValue,
        int groupByColIndex,
        int aggColIndex,
        String aggFunction
) {}
