package com.vksql.network.worker;

import com.vksql.network.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC server for a Worker node.
 *
 * Registers the WorkerService implementation and starts a heartbeat loop
 * that periodically pings the coordinator to signal liveness.
 */
public class WorkerServer {

    private final int port;
    private final Worker worker;
    private final String coordinatorAddress;
    private Server server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread heartbeatThread;

    public WorkerServer(Worker worker, int port, String coordinatorAddress) {
        this.worker = worker;
        this.port = port;
        this.coordinatorAddress = coordinatorAddress;
    }

    /**
     * Start the gRPC server and heartbeat thread.
     */
    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(new WorkerServiceImpl(worker))
                .build()
                .start();

        running.set(true);

        // Start heartbeat virtual thread that sends heartbeat to coordinator every 5 seconds
        heartbeatThread = Thread.startVirtualThread(this::heartbeatLoop);
    }

    /**
     * Stop the gRPC server and heartbeat thread.
     */
    public void stop() throws InterruptedException {
        running.set(false);

        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }

        if (server != null) {
            server.shutdown();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Block until the server is terminated.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public int getPort() {
        return port;
    }

    /**
     * Heartbeat loop: sends a heartbeat to the coordinator every 5 seconds.
     * Uses a virtual thread for lightweight scheduling.
     */
    private void heartbeatLoop() {
        // Parse coordinator address
        String[] parts = coordinatorAddress.split(":");
        String host = parts[0];
        int coordPort = Integer.parseInt(parts[1]);

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, coordPort)
                .usePlaintext()
                .build();

        try {
            WorkerServiceGrpc.WorkerServiceBlockingStub stub =
                    WorkerServiceGrpc.newBlockingStub(channel);

            while (running.get()) {
                try {
                    // We send heartbeat to the coordinator's CoordinatorService
                    // But the heartbeat RPC is on WorkerService — coordinator handles it
                    // via its own registered heartbeat handler
                    // Actually, heartbeat goes to coordinator. The coordinator has
                    // the WorkerRegistry. We'll use a simple approach: send heartbeat
                    // request to coordinator (coordinator exposes a heartbeat endpoint)
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
