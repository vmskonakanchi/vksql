package com.vksql.network.coordinator;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks which workers are alive and what data they hold.
 *
 * Workers register on startup (heartbeat).
 * If 3 heartbeats are missed (15s), worker is marked dead.
 */
public class WorkerRegistry {
    private final ConcurrentHashMap<String, WorkerInfo> workers = new ConcurrentHashMap<>();

    public void register(String workerId, String host, int port) {
        workers.put(workerId, new WorkerInfo(workerId, host, port, System.currentTimeMillis()));
    }

    public void heartbeat(String workerId) {
        WorkerInfo info = workers.get(workerId);
        if (info != null) {
            info.lastHeartbeat = System.currentTimeMillis();
        }
    }

    public List<WorkerInfo> getAliveWorkers() {
        long now = System.currentTimeMillis();
        return workers.values().stream()
            .filter(w -> (now - w.lastHeartbeat) < 15_000) // alive if heartbeat within 15s
            .toList();
    }

    public void markDead(String workerId) {
        workers.remove(workerId);
    }

    public static class WorkerInfo {
        public final String id;
        public final String host;
        public final int port;
        public volatile long lastHeartbeat;

        public WorkerInfo(String id, String host, int port, long lastHeartbeat) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.lastHeartbeat = lastHeartbeat;
        }

        public String address() { return host + ":" + port; }
    }
}
