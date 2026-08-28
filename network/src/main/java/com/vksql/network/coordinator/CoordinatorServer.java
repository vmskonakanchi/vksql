package com.vksql.network.coordinator;

import com.vksql.network.proto.*;
import com.vksql.storage.format.Schema;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * gRPC server for the Coordinator node.
 *
 * Hosts the CoordinatorService (for client queries) and also handles
 * worker heartbeat registration to track alive workers.
 */
public class CoordinatorServer {

    private final int port;
    private final Coordinator coordinator;
    private final Schema schema;
    private Server server;

    public CoordinatorServer(int port, Schema schema) {
        this.port = port;
        this.coordinator = new Coordinator(port);
        this.schema = schema;
    }

    /**
     * Start the gRPC server with both CoordinatorService and a heartbeat handler.
     */
    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(new CoordinatorServiceImpl(coordinator, schema))
                .addService(new HeartbeatServiceImpl(coordinator.getWorkerRegistry()))
                .build()
                .start();
    }

    /**
     * Stop the gRPC server.
     */
    public void stop() throws InterruptedException {
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

    /**
     * Get the coordinator instance for worker registration.
     */
    public Coordinator getCoordinator() {
        return coordinator;
    }

    public int getPort() {
        return port;
    }

    /**
     * Register a worker in the coordinator's registry.
     * Called when a worker connects or sends heartbeat.
     */
    public void registerWorker(String workerId, String host, int port) {
        coordinator.getWorkerRegistry().register(workerId, host, port);
    }

    /**
     * Internal gRPC service to handle worker heartbeats on the coordinator side.
     * Workers call the WorkerService.Heartbeat RPC, but the coordinator also
     * needs to receive heartbeats — so we expose a WorkerService on the coordinator
     * that just tracks the registration.
     */
    private static class HeartbeatServiceImpl extends WorkerServiceGrpc.WorkerServiceImplBase {

        private final WorkerRegistry workerRegistry;

        HeartbeatServiceImpl(WorkerRegistry workerRegistry) {
            this.workerRegistry = workerRegistry;
        }

        @Override
        public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
            // Update the worker's last heartbeat time
            workerRegistry.heartbeat(request.getWorkerId());

            HeartbeatResponse response = HeartbeatResponse.newBuilder()
                    .setAcknowledged(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void executeTask(TaskRequest request, StreamObserver<TaskResult> responseObserver) {
            // Not implemented on coordinator side — only workers execute tasks
            responseObserver.onError(io.grpc.Status.UNIMPLEMENTED
                    .withDescription("Coordinator does not execute tasks")
                    .asRuntimeException());
        }
    }
}
