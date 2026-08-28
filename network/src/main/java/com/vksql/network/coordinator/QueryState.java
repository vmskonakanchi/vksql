package com.vksql.network.coordinator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the state of a running distributed query.
 */
public class QueryState {
    public enum Status { PLANNING, RUNNING, COMPLETED, FAILED }

    private final String queryId;
    private final String sql;
    private volatile Status status = Status.PLANNING;
    private final List<TaskAssignment> tasks = new CopyOnWriteArrayList<>();
    private final AtomicInteger completedTasks = new AtomicInteger(0);

    public QueryState(String queryId, String sql) {
        this.queryId = queryId;
        this.sql = sql;
    }

    public String queryId() { return queryId; }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public void addTask(TaskAssignment task) { tasks.add(task); }
    public int totalTasks() { return tasks.size(); }
    public int markTaskComplete() { return completedTasks.incrementAndGet(); }
    public boolean allDone() { return completedTasks.get() >= tasks.size(); }
}
