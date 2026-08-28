package com.vksql.vector;

import java.util.PriorityQueue;

/**
 * Brute-force k-nearest-neighbor search over a {@link VectorColumn}.
 * Computes distance from the query to every vector — O(n) per query.
 * This is the baseline implementation before HNSW indexing.
 */
public final class BruteForceKnn {

    private final VectorColumn vectors;
    private final DistanceFunction distFunc;

    /**
     * Creates a brute-force KNN searcher.
     *
     * @param vectors  the vector column to search over
     * @param distFunc the distance function to use
     */
    public BruteForceKnn(VectorColumn vectors, DistanceFunction distFunc) {
        this.vectors = vectors;
        this.distFunc = distFunc;
    }

    /**
     * Finds the k nearest neighbors to the query vector.
     *
     * @param query the query vector
     * @param k     number of nearest neighbors to return
     * @return array of indices (length k) of the nearest neighbors, ordered closest first
     */
    public int[] search(float[] query, int k) {
        int n = vectors.size();
        int resultSize = Math.min(k, n);

        // Max-heap: keeps the k closest vectors seen so far.
        // The head of the heap is the farthest of the current top-k,
        // so we can efficiently evict it when we find something closer.
        PriorityQueue<long[]> maxHeap = new PriorityQueue<>(resultSize, (a, b) -> {
            // Compare by distance descending (max-heap behavior)
            return Float.compare(
                Float.intBitsToFloat((int) b[1]),
                Float.intBitsToFloat((int) a[1])
            );
        });

        for (int i = 0; i < n; i++) {
            float dist = distFunc.compute(query, vectors.get(i));
            int distBits = Float.floatToIntBits(dist);

            if (maxHeap.size() < resultSize) {
                maxHeap.offer(new long[]{i, distBits});
            } else {
                // Check if this vector is closer than the farthest in our top-k
                float headDist = Float.intBitsToFloat((int) maxHeap.peek()[1]);
                if (dist < headDist) {
                    maxHeap.poll();
                    maxHeap.offer(new long[]{i, distBits});
                }
            }
        }

        // Extract results, sorted closest first
        int[] result = new int[maxHeap.size()];
        int idx = result.length - 1;
        while (!maxHeap.isEmpty()) {
            result[idx--] = (int) maxHeap.poll()[0];
        }
        return result;
    }
}
