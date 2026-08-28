package com.vksql.vector;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests brute-force KNN search with all distance functions.
 * Uses 10,000 vectors of dimension 128, searches for k=10 nearest neighbors.
 */
class VectorSearchTest {

    private static final int NUM_VECTORS = 10_000;
    private static final int DIMENSION = 128;
    private static final int K = 10;
    private static final long SEED = 42;

    private static VectorColumn vectorColumn;
    private static float[] queryVector;

    @BeforeAll
    static void setup() {
        Random rng = new Random(SEED);
        float[][] vectors = new float[NUM_VECTORS][DIMENSION];
        for (int i = 0; i < NUM_VECTORS; i++) {
            for (int j = 0; j < DIMENSION; j++) {
                vectors[i][j] = rng.nextFloat();
            }
        }
        vectorColumn = new VectorColumn(DIMENSION, vectors);

        // Generate a query vector
        queryVector = new float[DIMENSION];
        for (int j = 0; j < DIMENSION; j++) {
            queryVector[j] = rng.nextFloat();
        }
    }

    @Test
    void testL2Search() {
        BruteForceKnn knn = new BruteForceKnn(vectorColumn, DistanceFunction.L2);
        int[] results = knn.search(queryVector, K);

        assertEquals(K, results.length);
        assertDistancesSorted(results, DistanceFunction.L2);
        assertNoDuplicates(results);
    }

    @Test
    void testCosineSearch() {
        BruteForceKnn knn = new BruteForceKnn(vectorColumn, DistanceFunction.COSINE);
        int[] results = knn.search(queryVector, K);

        assertEquals(K, results.length);
        assertDistancesSorted(results, DistanceFunction.COSINE);
        assertNoDuplicates(results);
    }

    @Test
    void testDotProductSearch() {
        BruteForceKnn knn = new BruteForceKnn(vectorColumn, DistanceFunction.DOT_PRODUCT);
        int[] results = knn.search(queryVector, K);

        assertEquals(K, results.length);
        assertDistancesSorted(results, DistanceFunction.DOT_PRODUCT);
        assertNoDuplicates(results);
    }

    @Test
    void testBruteForceIsExact() {
        // Verify brute-force returns the true top-k by exhaustive comparison
        DistanceFunction distFunc = DistanceFunction.L2;
        BruteForceKnn knn = new BruteForceKnn(vectorColumn, distFunc);
        int[] results = knn.search(queryVector, K);

        // Compute all distances and sort to get ground truth
        float[] allDistances = new float[NUM_VECTORS];
        for (int i = 0; i < NUM_VECTORS; i++) {
            allDistances[i] = distFunc.compute(queryVector, vectorColumn.get(i));
        }

        // Find the k-th smallest distance
        float kthDistance = distFunc.compute(queryVector, vectorColumn.get(results[K - 1]));

        // All results should have distance <= kth distance
        for (int idx : results) {
            float dist = distFunc.compute(queryVector, vectorColumn.get(idx));
            assertTrue(dist <= kthDistance + 1e-6f,
                "Result index " + idx + " has distance " + dist + " > kth distance " + kthDistance);
        }

        // No non-result vector should be closer than kth distance
        int closerCount = 0;
        for (int i = 0; i < NUM_VECTORS; i++) {
            if (allDistances[i] < kthDistance - 1e-6f) {
                closerCount++;
            }
        }
        assertTrue(closerCount < K,
            "Found " + closerCount + " vectors closer than kth result — brute force missed some");
    }

    @Test
    void testVectorColumnBasics() {
        assertEquals(DIMENSION, vectorColumn.dimension());
        assertEquals(NUM_VECTORS, vectorColumn.size());
        assertNotNull(vectorColumn.get(0));
        assertEquals(DIMENSION, vectorColumn.get(0).length);
        assertNotNull(vectorColumn.raw());
        assertEquals(NUM_VECTORS, vectorColumn.raw().length);
    }

    @Test
    void benchmarkAllDistanceFunctions() {
        int numQueries = 1000;
        Random rng = new Random(123);

        for (DistanceFunction distFunc : DistanceFunction.values()) {
            BruteForceKnn knn = new BruteForceKnn(vectorColumn, distFunc);

            // Warm up
            for (int i = 0; i < 10; i++) {
                float[] q = randomVector(rng);
                knn.search(q, K);
            }

            // Benchmark
            long start = System.nanoTime();
            for (int i = 0; i < numQueries; i++) {
                float[] q = randomVector(rng);
                knn.search(q, K);
            }
            long elapsed = System.nanoTime() - start;

            double queriesPerSec = numQueries / (elapsed / 1_000_000_000.0);
            System.out.printf("[%s] %d queries over %d vectors (dim=%d): %.0f queries/sec%n",
                distFunc.name(), numQueries, NUM_VECTORS, DIMENSION, queriesPerSec);
        }
    }

    // --- Helpers ---

    private void assertDistancesSorted(int[] indices, DistanceFunction distFunc) {
        float prevDist = Float.NEGATIVE_INFINITY;
        for (int idx : indices) {
            float dist = distFunc.compute(queryVector, vectorColumn.get(idx));
            assertTrue(dist >= prevDist - 1e-6f,
                "Distances not sorted: " + prevDist + " > " + dist);
            prevDist = dist;
        }
    }

    private void assertNoDuplicates(int[] indices) {
        for (int i = 0; i < indices.length; i++) {
            for (int j = i + 1; j < indices.length; j++) {
                assertNotEquals(indices[i], indices[j], "Duplicate index found: " + indices[i]);
            }
        }
    }

    private float[] randomVector(Random rng) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = rng.nextFloat();
        }
        return v;
    }
}
