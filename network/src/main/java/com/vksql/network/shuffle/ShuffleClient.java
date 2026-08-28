package com.vksql.network.shuffle;

/**
 * ShuffleClient: sends intermediate results from one worker to another.
 *
 * Used when a query needs to repartition data between stages.
 * Example: partial aggregates from Stage 0 need to be reshuffled
 * by group-by key to the worker that handles the final aggregate.
 *
 * Uses gRPC streaming: PushBatch(stream RecordBatch) → ShuffleAck
 *
 * Implementation steps:
 * 1. Open a gRPC channel to the target worker
 * 2. Create a streaming call to ShuffleService.PushBatch
 * 3. For each batch: hash rows by shuffle key, split into sub-batches per target
 * 4. Send sub-batches to appropriate targets
 * 5. Use back-pressure (check isReady() before sending)
 */
public class ShuffleClient {
    private final String targetAddress; // host:port

    public ShuffleClient(String targetAddress) {
        this.targetAddress = targetAddress;
    }

    // TODO: implement with gRPC streaming
    // public void sendBatch(RecordBatch batch) { ... }
    // public void finish() { ... }
}
