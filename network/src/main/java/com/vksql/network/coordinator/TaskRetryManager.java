package com.vksql.network.coordinator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages task retry logic when workers die.
 *
 * Tracks which tasks are assigned to which workers, and provides methods
 * to reassign failed tasks to alive workers using round-robin selection.
 *
 * Tasks that exceed maxRetries are marked as permanently failed.
 */
public class TaskRetryManager {

    private final int maxRetries;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    // workerId -> list of tasks assigned to that worker
    private final ConcurrentHashMap<String, List<TaskAssignment>> workerTasks = new ConcurrentHashMap<>();

    // taskKey -> retry count
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();

    // permanently failed tasks
    private final Set<String> permanentlyFailed = ConcurrentHashMap.newKeySet();

    /**
     * Create a TaskRetryManager with default max retries of 3.
     */
    public TaskRetryManager() {
        this(3);
    }

    /**
     * Create a TaskRetryManager with a configurable max retry count.
     */
    public TaskRetryManager(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * Register a task assignment, tracking which worker owns it.
     */
    public void assignTask(TaskAssignment task) {
        workerTasks.computeIfAbsent(task.workerId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(task);
    }

    /**
     * Get all tasks that were assigned to a dead worker.
     */
    public List<TaskAssignment> getFailedTasks(String workerId) {
        List<TaskAssignment> tasks = workerTasks.remove(workerId);
        return tasks != null ? tasks : List.of();
    }

    /**
     * Reassign a failed task to another alive worker using round-robin selection.
     *
     * Returns null if the task has exceeded max retries (permanently failed).
     * Returns null if there are no alive workers available.
     */
    public TaskAssignment reassign(TaskAssignment failedTask, List<WorkerRegistry.WorkerInfo> aliveWorkers) {
        if (aliveWorkers == null || aliveWorkers.isEmpty()) {
            return null;
        }

        String taskKey = buildTaskKey(failedTask);

        // Increment retry count
        int retries = retryCounts.merge(taskKey, 1, Integer::sum);

        // Check if max retries exceeded
        if (retries > maxRetries) {
            permanentlyFailed.add(taskKey);
            return null;
        }

        // Pick next worker via round-robin
        int index = Math.abs(roundRobinCounter.getAndIncrement() % aliveWorkers.size());
        WorkerRegistry.WorkerInfo newWorker = aliveWorkers.get(index);

        // Create new task assignment with the new worker
        TaskAssignment newTask = new TaskAssignment(
                newWorker.id,
                failedTask.tableName(),
                failedTask.filterColumn(),
                failedTask.filterOp(),
                failedTask.filterValue(),
                failedTask.groupByColIndex(),
                failedTask.aggColIndex(),
                failedTask.aggFunction()
        );

        // Track the new assignment
        assignTask(newTask);

        return newTask;
    }

    /**
     * Check if a task has been permanently failed (exceeded max retries).
     */
    public boolean isPermanentlyFailed(TaskAssignment task) {
        return permanentlyFailed.contains(buildTaskKey(task));
    }

    /**
     * Get the current retry count for a task.
     */
    public int getRetryCount(TaskAssignment task) {
        return retryCounts.getOrDefault(buildTaskKey(task), 0);
    }

    /**
     * Get all permanently failed task keys.
     */
    public Set<String> getPermanentlyFailedTasks() {
        return Set.copyOf(permanentlyFailed);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Build a unique key for a task based on its content (excluding workerId,
     * since the same logical task may be reassigned to different workers).
     */
    private String buildTaskKey(TaskAssignment task) {
        return task.tableName() + ":" + task.filterColumn() + ":" + task.filterOp() + ":"
                + task.filterValue() + ":" + task.groupByColIndex() + ":" + task.aggColIndex()
                + ":" + task.aggFunction();
    }
}
