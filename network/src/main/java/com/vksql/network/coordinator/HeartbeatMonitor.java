package com.vksql.network.coordinator;

import java.time.Duration;
import java.util.List;

/**
 * Monitors worker heartbeats and marks workers as dead if they haven't
 * sent a heartbeat within the configured timeout.
 *
 * Runs a background virtual thread that checks the WorkerRegistry at a
 * configurable interval.
 */
public class HeartbeatMonitor {

    private final WorkerRegistry workerRegistry;
    private final long timeoutMillis;
    private final long checkIntervalMillis;
    private volatile boolean running;
    private Thread monitorThread;

    /**
     * Create a HeartbeatMonitor with default settings (15s timeout, 5s check interval).
     */
    public HeartbeatMonitor(WorkerRegistry workerRegistry) {
        this(workerRegistry, Duration.ofSeconds(15), Duration.ofSeconds(5));
    }

    /**
     * Create a HeartbeatMonitor with configurable timeout and check interval.
     * Useful for testing with shorter durations.
     */
    public HeartbeatMonitor(WorkerRegistry workerRegistry, Duration timeout, Duration checkInterval) {
        this.workerRegistry = workerRegistry;
        this.timeoutMillis = timeout.toMillis();
        this.checkIntervalMillis = checkInterval.toMillis();
    }

    /**
     * Start the background heartbeat monitoring thread.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        monitorThread = Thread.startVirtualThread(this::monitorLoop);
    }

    /**
     * Stop the heartbeat monitor.
     */
    public void stop() {
        running = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
    }

    private void monitorLoop() {
        while (running) {
            try {
                Thread.sleep(checkIntervalMillis);
                checkWorkers();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Check all workers and mark any with an expired heartbeat as dead.
     * Package-private for testability.
     */
    void checkWorkers() {
        long now = System.currentTimeMillis();
        List<WorkerRegistry.WorkerInfo> allWorkers = workerRegistry.getAllWorkers();
        for (WorkerRegistry.WorkerInfo worker : allWorkers) {
            if ((now - worker.lastHeartbeat) > timeoutMillis) {
                workerRegistry.markDead(worker.id);
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}
