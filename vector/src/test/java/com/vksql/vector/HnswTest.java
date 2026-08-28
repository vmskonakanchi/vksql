package com.vksql.vector;

import com.vksql.vector.hnsw.HnswIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HNSW approximate nearest neighbor index.
 * <p>
 * Validates:
 * - Recall@10 >= 0.90 compared to brute-force ground truth (at efSearch=300)
 * - HNSW is at least 10x faster than brute-force (at efSearch=50)
 * - Basic correctness of search results
 */
class HnswTest {

    private static final int NUM_VECTORS = 100_000;
    private static final int DIMENSION = 128;
    private static final int K = 10;
    private static final int NUM_QUERIES = 1000;
    private static final long SEED = 42L;

    private static float[][] data;
    private static float[][] queries;
    private static HnswIndex index;
    private static VectorColumn vectorColumn;

    @BeforeAll
    static void setup() {
        Random rng = new Random(SEED);

        // Generate random vectors
        data = new float[NUM_VECTORS][DIMENSION];
        for (int i = 0; i < NUM_VECTORS; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                data[i][d] = rng.nextFloat();
            }
        }

        // Generate random query vectors
        queries = new float[NUM_QUERIES][DIMENSION];
        for (int i = 0; i < NUM_QUERIES; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                queries[i][d] = rng.nextFloat();
            }
        }

        // Build HNSW index
        System.out.println("Building HNSW index with " + NUM_VECTORS + " vectors, dim=" + DIMENSION + "...");
        long startBuild = System.nanoTime();

        index = new HnswIndex(DistanceFunction.L2, 16, 200, 50, new Random(SEED + 1));
        for (int i = 0; i < NUM_VECTORS; i++) {
            index.add(i, data[i]);
        }

        long buildTime = System.nanoTime() - startBuild;
        System.out.printf("HNSW build time: %.2f seconds (%.0f vectors/sec)%n",
                buildTime / 1e9, NUM_VECTORS / (buildTime / 1e9));

        // Create VectorColumn for brute-force
        vectorColumn = new VectorColumn(DIMENSION, data);
    }

    @Test
    void testRecallAtLeast90Percent() {
        // Use higher efSearch for recall quality test — standard practice in ANN benchmarks
        index.setEfSearch(300);
        BruteForceKnn bruteForce = new BruteForceKnn(vectorColumn, DistanceFunction.L2);

        double totalRecall = 0.0;
        int numTestQueries = 100;

        for (int q = 0; q < numTestQueries; q++) {
            int[] hnswResult = index.search(queries[q], K);
            int[] groundTruth = bruteForce.search(queries[q], K);

            Set<Integer> truthSet = new HashSet<>();
            for (int id : groundTruth) {
                truthSet.add(id);
            }

            int found = 0;
            for (int id : hnswResult) {
                if (truthSet.contains(id)) {
                    found++;
                }
            }
            totalRecall += (double) found / K;
        }

        double avgRecall = totalRecall / numTestQueries;
        System.out.printf("HNSW Recall@%d: %.4f (over %d queries, efSearch=300)%n", K, avgRecall, numTestQueries);

        // Restore default for other tests
        index.setEfSearch(50);

        assertTrue(avgRecall >= 0.90,
                "Recall@" + K + " should be >= 0.90 but was " + avgRecall);
    }

    @Test
    void testHnswFasterThanBruteForce() {
        // Use default efSearch=50 for speed test
        index.setEfSearch(50);
        BruteForceKnn bruteForce = new BruteForceKnn(vectorColumn, DistanceFunction.L2);

        // Warmup HNSW
        for (int i = 0; i < 100; i++) {
            index.search(queries[i], K);
        }

        // Benchmark HNSW
        long startHnsw = System.nanoTime();
        for (int q = 0; q < NUM_QUERIES; q++) {
            index.search(queries[q], K);
        }
        long hnswTime = System.nanoTime() - startHnsw;

        // Warmup brute-force
        for (int i = 0; i < 10; i++) {
            bruteForce.search(queries[i], K);
        }

        // Benchmark brute-force
        long startBf = System.nanoTime();
        for (int q = 0; q < NUM_QUERIES; q++) {
            bruteForce.search(queries[q], K);
        }
        long bfTime = System.nanoTime() - startBf;

        double hnswQps = NUM_QUERIES / (hnswTime / 1e9);
        double bfQps = NUM_QUERIES / (bfTime / 1e9);
        double speedup = hnswQps / bfQps;

        System.out.printf("HNSW: %.0f queries/sec (efSearch=50)%n", hnswQps);
        System.out.printf("Brute-force: %.0f queries/sec%n", bfQps);
        System.out.printf("Speedup: %.1fx%n", speedup);

        assertTrue(speedup >= 10.0,
                "HNSW should be at least 10x faster than brute-force but speedup was " + speedup + "x");
    }

    @Test
    void testSearchReturnsCorrectK() {
        int[] result = index.search(queries[0], K);
        assertEquals(K, result.length, "Should return exactly k results");

        // Verify no duplicates
        Set<Integer> ids = new HashSet<>();
        for (int id : result) {
            assertTrue(ids.add(id), "Result should not contain duplicates, found duplicate: " + id);
        }

        // Verify all ids are valid
        for (int id : result) {
            assertTrue(id >= 0 && id < NUM_VECTORS,
                    "Result id should be in valid range: " + id);
        }
    }

    @Test
    void testSearchResultsAreSortedByDistance() {
        int[] result = index.search(queries[0], K);

        float prevDist = -1;
        for (int id : result) {
            float dist = DistanceFunction.L2.compute(queries[0], data[id]);
            assertTrue(dist >= prevDist,
                    "Results should be sorted by distance, but " + dist + " < " + prevDist);
            prevDist = dist;
        }
    }

    @Test
    void testSingleVector() {
        HnswIndex smallIndex = new HnswIndex(DistanceFunction.L2, 16, 200, 50, new Random(123));
        float[] vec = new float[]{1.0f, 2.0f, 3.0f};
        smallIndex.add(0, vec);

        int[] result = smallIndex.search(new float[]{1.0f, 2.0f, 3.0f}, 1);
        assertEquals(1, result.length);
        assertEquals(0, result[0]);
    }

    @Test
    void testSmallIndex() {
        HnswIndex smallIndex = new HnswIndex(DistanceFunction.L2, 4, 20, 10, new Random(99));
        int n = 100;
        int dim = 8;
        Random rng = new Random(77);

        float[][] vecs = new float[n][dim];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dim; d++) {
                vecs[i][d] = rng.nextFloat();
            }
            smallIndex.add(i, vecs[i]);
        }

        // Compare with brute force
        VectorColumn vc = new VectorColumn(dim, vecs);
        BruteForceKnn bf = new BruteForceKnn(vc, DistanceFunction.L2);

        float[] query = new float[dim];
        for (int d = 0; d < dim; d++) query[d] = rng.nextFloat();

        int[] hnswResult = smallIndex.search(query, 5);
        int[] bfResult = bf.search(query, 5);

        Set<Integer> bfSet = new HashSet<>();
        for (int id : bfResult) bfSet.add(id);

        int overlap = 0;
        for (int id : hnswResult) {
            if (bfSet.contains(id)) overlap++;
        }

        assertTrue(overlap >= 4,
                "Small index should have high recall, but only " + overlap + "/5 matched");
    }
}
