package com.vksql.network.coordinator;

public record TaskAssignment(String workerId, String taskId, int stageId, String tableName) {
}
