package com.vksql.network;

import com.vksql.network.coordinator.HeartbeatMonitor;
import com.vksql.network.coordinator.TaskAssignment;
import com.vksql.network.coordinator.TaskRetryManager;
import com.vksql.network.coordinator.WorkerRegistry;
import com.vksql.network.coordinator.WorkerRegistry.WorkerInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fault tolerance: heartbeat timeout detection and task retry logic.
 * Uses short timeouts for fast test execution (no gRPC needed).
 */
class FaultToleranceTest {

    // ========== Heartbeat Timeout Tests ==========

    @Test
    void heartbeatTimeout_workerMarkedDead_afterTimeout() throws InterruptedException {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register("worker-1", "localhost", 9001);

        // Use very short timeout (100ms) and check interval (50ms) for testing
        HeartbeatMonitor monitor = new HeartbeatMonitor(
                registry,
                Duration.ofMillis(100),
                Duration.ofMillis(50)
        );

        // Worker is alive initially
        assertEquals(1, registry.getAliveWorkers().size());
        assertEquals(1, registry.getAllWorkers().size());

        monitor.start();

        // Wait enough time for timeout to expire and monitor to detect it
        Thread.sleep(300);

        monitor.stop();

        // Worker should be marked dead (removed from registry)
        assertTrue(registry.getAllWorkers().isEmpty(),
                "Worker should be marked dead after heartbeat timeout");
        assertTrue(registry.getAliveWorkers().isEmpty(),
                "No alive workers should remain");
    }

    @Test
    void heartbeatTimeout_workerStaysAlive_withHeartbeats() throws InterruptedException {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register("worker-1", "localhost", 9001);

        HeartbeatMonitor monitor = new HeartbeatMonitor(
                registry,
                Duration.ofMillis(200),
                Duration.ofMillis(50)
        );

        monitor.start();

        // Keep sending heartbeats every 50ms for 400ms
        for (int i = 0; i < 8; i++) {
            Thread.sleep(50);
            registry.heartbeat("worker-1");
        }

        monitor.stop();

        // Worker should still be alive because we kept sending heartbeats
        assertEquals(1, registry.getAllWorkers().size(),
                "Worker should remain alive when heartbeats are sent");
    }

    @Test
    void heartbeatTimeout_onlyDeadWorkersRemoved() throws InterruptedException {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register("worker-1", "localhost", 9001);
        registry.register("worker-2", "localhost", 9002);

        HeartbeatMonitor monitor = new HeartbeatMonitor(
                registry,
                Duration.ofMillis(100),
                Duration.ofMillis(50)
        );

        monitor.start();

        // Only send heartbeats for worker-2
        for (int i = 0; i < 6; i++) {
            Thread.sleep(50);
            registry.heartbeat("worker-2");
        }

        monitor.stop();

        // worker-1 should be dead, worker-2 should be alive
        List<WorkerInfo> alive = registry.getAllWorkers();
        assertEquals(1, alive.size(), "Only one worker should remain");
        assertEquals("worker-2", alive.get(0).id, "worker-2 should be the surviving worker");
    }

    // ========== Task Retry Tests ==========

    @Test
    void taskRetry_failedTasksReassignedToAliveWorker() {
        TaskRetryManager retryManager = new TaskRetryManager();

        // Create task assigned to worker-1
        TaskAssignment task = new TaskAssignment(
                "worker-1", "orders", "price", ">", 250, 0, 1, "sum"
        );
        retryManager.assignTask(task);

        // Simulate worker-1 death: get its failed tasks
        List<TaskAssignment> failedTasks = retryManager.getFailedTasks("worker-1");
        assertEquals(1, failedTasks.size());
        assertEquals("worker-1", failedTasks.get(0).workerId());

        // Reassign to an alive worker
        List<WorkerInfo> aliveWorkers = List.of(
                new WorkerInfo("worker-2", "localhost", 9002, System.currentTimeMillis()),
                new WorkerInfo("worker-3", "localhost", 9003, System.currentTimeMillis())
        );

        TaskAssignment reassigned = retryManager.reassign(failedTasks.get(0), aliveWorkers);
        assertNotNull(reassigned, "Task should be reassigned");
        assertNotEquals("worker-1", reassigned.workerId(),
                "Task should not be reassigned to the dead worker");
        assertTrue(reassigned.workerId().equals("worker-2") || reassigned.workerId().equals("worker-3"),
                "Task should be assigned to one of the alive workers");

        // Verify task content is preserved
        assertEquals("orders", reassigned.tableName());
        assertEquals("price", reassigned.filterColumn());
        assertEquals(">", reassigned.filterOp());
        assertEquals(250, reassigned.filterValue());
        assertEquals(0, reassigned.groupByColIndex());
        assertEquals(1, reassigned.aggColIndex());
        assertEquals("sum", reassigned.aggFunction());
    }

    @Test
    void taskRetry_roundRobinDistribution() {
        TaskRetryManager retryManager = new TaskRetryManager();

        List<WorkerInfo> aliveWorkers = List.of(
                new WorkerInfo("worker-A", "localhost", 9001, System.currentTimeMillis()),
                new WorkerInfo("worker-B", "localhost", 9002, System.currentTimeMillis())
        );

        // Create distinct tasks so they have different keys
        TaskAssignment task1 = new TaskAssignment(
                "dead-worker", "orders", "price", ">", 100, 0, 1, "sum"
        );
        TaskAssignment task2 = new TaskAssignment(
                "dead-worker", "orders", "price", ">", 200, 0, 1, "sum"
        );
        TaskAssignment task3 = new TaskAssignment(
                "dead-worker", "orders", "price", ">", 300, 0, 1, "sum"
        );

        // Reassign should distribute tasks across alive workers
        TaskAssignment r1 = retryManager.reassign(task1, aliveWorkers);
        TaskAssignment r2 = retryManager.reassign(task2, aliveWorkers);
        TaskAssignment r3 = retryManager.reassign(task3, aliveWorkers);

        assertNotNull(r1);
        assertNotNull(r2);
        assertNotNull(r3);

        // Round-robin: should alternate between workers
        assertNotEquals(r1.workerId(), r2.workerId(),
                "Round-robin should alternate between workers");
    }

    @Test
    void taskRetry_maxRetriesExceeded_markedPermanentlyFailed() {
        TaskRetryManager retryManager = new TaskRetryManager(3); // max 3 retries

        TaskAssignment task = new TaskAssignment(
                "worker-1", "orders", "price", ">", 250, 0, 1, "sum"
        );

        List<WorkerInfo> aliveWorkers = List.of(
                new WorkerInfo("worker-2", "localhost", 9002, System.currentTimeMillis())
        );

        // Retry 1 — should succeed
        TaskAssignment retry1 = retryManager.reassign(task, aliveWorkers);
        assertNotNull(retry1, "Retry 1 should succeed");
        assertEquals(1, retryManager.getRetryCount(task));

        // Retry 2 — should succeed
        TaskAssignment retry2 = retryManager.reassign(task, aliveWorkers);
        assertNotNull(retry2, "Retry 2 should succeed");
        assertEquals(2, retryManager.getRetryCount(task));

        // Retry 3 — should succeed (this is the last allowed retry)
        TaskAssignment retry3 = retryManager.reassign(task, aliveWorkers);
        assertNotNull(retry3, "Retry 3 should succeed");
        assertEquals(3, retryManager.getRetryCount(task));

        // Retry 4 — should fail (exceeds max retries)
        TaskAssignment retry4 = retryManager.reassign(task, aliveWorkers);
        assertNull(retry4, "Retry 4 should return null — max retries exceeded");
        assertTrue(retryManager.isPermanentlyFailed(task),
                "Task should be marked as permanently failed");
    }

    @Test
    void taskRetry_noAliveWorkers_returnsNull() {
        TaskRetryManager retryManager = new TaskRetryManager();

        TaskAssignment task = new TaskAssignment(
                "worker-1", "orders", "price", ">", 250, 0, 1, "sum"
        );

        // No alive workers available
        TaskAssignment result = retryManager.reassign(task, List.of());
        assertNull(result, "Should return null when no alive workers");

        result = retryManager.reassign(task, null);
        assertNull(result, "Should return null when worker list is null");
    }

    @Test
    void taskRetry_multipleTasksOnDeadWorker() {
        TaskRetryManager retryManager = new TaskRetryManager();

        TaskAssignment task1 = new TaskAssignment(
                "worker-1", "orders", "price", ">", 100, 0, 1, "sum"
        );
        TaskAssignment task2 = new TaskAssignment(
                "worker-1", "lineitem", "quantity", ">", 50, 0, 2, "count"
        );

        retryManager.assignTask(task1);
        retryManager.assignTask(task2);

        // Worker dies — get all its tasks
        List<TaskAssignment> failedTasks = retryManager.getFailedTasks("worker-1");
        assertEquals(2, failedTasks.size(), "Both tasks should be returned as failed");

        // Second call should return empty (tasks already retrieved)
        List<TaskAssignment> secondCall = retryManager.getFailedTasks("worker-1");
        assertTrue(secondCall.isEmpty(), "Second call should return empty list");
    }
}
