package com.vksql.vector.hnsw;

import com.vksql.vector.DistanceFunction;

import java.util.*;

/**
 * Hierarchical Navigable Small World (HNSW) approximate nearest neighbor index.
 * <p>
 * Implements the HNSW algorithm for fast approximate k-NN search on high-dimensional vectors.
 * Uses a multi-layer graph where each layer is a navigable small world graph with
 * exponentially decreasing number of nodes.
 * <p>
 * Features:
 * <ul>
 *   <li>Diversity-preserving neighbor selection heuristic with fallback to closest neighbors</li>
 *   <li>Multi-layer navigation with greedy descent and beam search</li>
 *   <li>Proper termination condition ensuring ef candidates are explored</li>
 * </ul>
 *
 * @see <a href="https://arxiv.org/abs/1603.09320">HNSW Paper (Malkov & Yashunin, 2018)</a>
 */
public final class HnswIndex {

    private final int m;              // Max connections per layer
    private final int mMax0;          // Max connections at layer 0 (5 * M for high-dim data)
    private final int efConstruction; // Build beam width
    private int efSearch;             // Query beam width

    private final double mL;          // Level generation factor: 1/ln(M)
    private final DistanceFunction distFunc;
    private final Random random;

    // Storage
    private final ArrayList<float[]> vectors;
    // Graph: graph[nodeId][layer] = int[] of neighbor ids
    private final ArrayList<int[][]> graph;

    private int entryPoint;
    private int maxLevel;

    /** Creates an HNSW index with default parameters: M=16, efConstruction=200, efSearch=50. */
    public HnswIndex(DistanceFunction distFunc) {
        this(distFunc, 16, 200, 50);
    }

    /** Creates an HNSW index with specified parameters. */
    public HnswIndex(DistanceFunction distFunc, int m, int efConstruction, int efSearch) {
        this(distFunc, m, efConstruction, efSearch, new Random());
    }

    /** Creates an HNSW index with specified parameters and random seed. */
    public HnswIndex(DistanceFunction distFunc, int m, int efConstruction, int efSearch, Random random) {
        this.distFunc = distFunc;
        this.m = m;
        this.mMax0 = 5 * m;
        this.efConstruction = efConstruction;
        this.efSearch = efSearch;
        this.mL = 1.0 / Math.log(m);
        this.random = random;

        this.vectors = new ArrayList<>();
        this.graph = new ArrayList<>();
        this.entryPoint = -1;
        this.maxLevel = -1;
    }

    /** Sets the ef (beam width) parameter for search queries. */
    public void setEfSearch(int efSearch) {
        this.efSearch = efSearch;
    }

    /** Returns the number of vectors in the index. */
    public int size() {
        return vectors.size();
    }

    /**
     * Inserts a vector into the index.
     */
    public void add(int id, float[] vector) {
        if (id != vectors.size()) {
            throw new IllegalArgumentException("Expected id=" + vectors.size() + " but got " + id);
        }

        vectors.add(vector);
        int level = randomLevel();

        int[][] nodeGraph = new int[level + 1][];
        for (int i = 0; i <= level; i++) {
            nodeGraph[i] = new int[0];
        }
        graph.add(nodeGraph);

        if (entryPoint == -1) {
            entryPoint = id;
            maxLevel = level;
            return;
        }

        int currObj = entryPoint;

        // Phase 1: Greedy descent from top to (level + 1)
        for (int lc = maxLevel; lc > level; lc--) {
            currObj = greedyClosest(vector, currObj, lc);
        }

        // Phase 2: Insert at each layer from min(level, maxLevel) down to 0
        for (int lc = Math.min(level, maxLevel); lc >= 0; lc--) {
            int maxConn = (lc == 0) ? mMax0 : m;

            // Search for ef_construction candidates at this layer
            List<int[]> topCandidates = searchLayerList(vector, currObj, efConstruction, lc);

            // Select neighbors using heuristic + fill
            int[] selected = selectNeighbors(vector, topCandidates, maxConn);
            graph.get(id)[lc] = selected;

            // Add reverse connections
            for (int neighborId : selected) {
                addReverseConnection(neighborId, id, lc, maxConn);
            }

            // Update entry for next layer (closest found)
            if (topCandidates.size() > 0) {
                currObj = topCandidates.get(0)[0];
            }
        }

        if (level > maxLevel) {
            entryPoint = id;
            maxLevel = level;
        }
    }

    /**
     * Searches for the k approximate nearest neighbors.
     */
    public int[] search(float[] query, int k) {
        if (entryPoint == -1) {
            return new int[0];
        }

        int currObj = entryPoint;

        // Greedy descent from top layer to layer 1
        for (int lc = maxLevel; lc > 0; lc--) {
            currObj = greedyClosest(query, currObj, lc);
        }

        // Beam search at layer 0
        int ef = Math.max(efSearch, k);
        List<int[]> topCandidates = searchLayerList(query, currObj, ef, 0);

        int resultSize = Math.min(k, topCandidates.size());
        int[] result = new int[resultSize];
        for (int i = 0; i < resultSize; i++) {
            result[i] = topCandidates.get(i)[0];
        }
        return result;
    }

    // ---- Internal ----

    /**
     * Select neighbors using diversity heuristic with fallback to closest.
     * A candidate is "diverse" if it's closer to the query than to any already-selected neighbor.
     * After selecting diverse candidates, fills remaining slots with closest unselected candidates.
     */
    private int[] selectNeighbors(float[] queryVec, List<int[]> candidates, int maxConn) {
        int numCandidates = candidates.size();
        if (numCandidates <= maxConn) {
            int[] result = new int[numCandidates];
            for (int i = 0; i < numCandidates; i++) {
                result[i] = candidates.get(i)[0];
            }
            return result;
        }

        // Phase 1: Select diverse candidates using heuristic
        boolean[] selected = new boolean[numCandidates];
        List<Integer> result = new ArrayList<>(maxConn);

        for (int i = 0; i < numCandidates && result.size() < maxConn; i++) {
            int candidateId = candidates.get(i)[0];
            float distToQuery = Float.intBitsToFloat(candidates.get(i)[1]);

            boolean isGood = true;
            for (int selectedId : result) {
                float distBetween = distFunc.compute(vectors.get(candidateId), vectors.get(selectedId));
                if (distBetween < distToQuery) {
                    isGood = false;
                    break;
                }
            }

            if (isGood) {
                result.add(candidateId);
                selected[i] = true;
            }
        }

        // Phase 2: Fill remaining slots with closest unselected (keepPrunedConnections)
        for (int i = 0; i < numCandidates && result.size() < maxConn; i++) {
            if (!selected[i]) {
                result.add(candidates.get(i)[0]);
            }
        }

        int[] arr = new int[result.size()];
        for (int i = 0; i < result.size(); i++) arr[i] = result.get(i);
        return arr;
    }

    /** Adds newNeighborId to nodeId's connections, pruning if over maxConn using closest selection. */
    private void addReverseConnection(int nodeId, int newNeighborId, int layer, int maxConn) {
        int[] current = graph.get(nodeId)[layer];

        if (current.length < maxConn) {
            int[] updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = newNeighborId;
            graph.get(nodeId)[layer] = updated;
        } else {
            // For existing nodes: use simple closest selection to ensure
            // early-inserted nodes get their connections updated to actual nearest neighbors.
            float[] nodeVec = vectors.get(nodeId);
            float newDist = distFunc.compute(nodeVec, vectors.get(newNeighborId));

            // Find the farthest current neighbor
            int farthestIdx = -1;
            float farthestDist = newDist;
            for (int i = 0; i < current.length; i++) {
                float d = distFunc.compute(nodeVec, vectors.get(current[i]));
                if (d > farthestDist) {
                    farthestDist = d;
                    farthestIdx = i;
                }
            }

            // Replace farthest with new neighbor if new is closer
            if (farthestIdx >= 0) {
                current[farthestIdx] = newNeighborId;
                graph.get(nodeId)[layer] = current;
            }
        }
    }

    private int greedyClosest(float[] query, int ep, int layer) {
        float bestDist = distFunc.compute(query, vectors.get(ep));
        boolean improved = true;
        while (improved) {
            improved = false;
            int[] neighbors = graph.get(ep)[layer];
            for (int n : neighbors) {
                float d = distFunc.compute(query, vectors.get(n));
                if (d < bestDist) {
                    bestDist = d;
                    ep = n;
                    improved = true;
                }
            }
        }
        return ep;
    }

    /**
     * Beam search at a single layer.
     * Returns list of [nodeId, distBits] sorted by distance ascending (closest first).
     */
    private List<int[]> searchLayerList(float[] query, int entryId, int ef, int layer) {
        float entryDist = distFunc.compute(query, vectors.get(entryId));

        BitSet visited = new BitSet(vectors.size());
        visited.set(entryId);

        // candidateSet: min-heap (closest first to process next)
        PriorityQueue<int[]> candidateSet = new PriorityQueue<>(
                (a, b) -> Float.compare(Float.intBitsToFloat(a[1]), Float.intBitsToFloat(b[1]))
        );
        // topCandidates: max-heap (farthest first for eviction)
        PriorityQueue<int[]> topCandidates = new PriorityQueue<>(
                (a, b) -> Float.compare(Float.intBitsToFloat(b[1]), Float.intBitsToFloat(a[1]))
        );

        int entryDistBits = Float.floatToRawIntBits(entryDist);
        candidateSet.add(new int[]{entryId, entryDistBits});
        topCandidates.add(new int[]{entryId, entryDistBits});
        float lowerBound = entryDist;

        while (!candidateSet.isEmpty()) {
            int[] current = candidateSet.poll();
            float currentDist = Float.intBitsToFloat(current[1]);

            // Stop: only when results are full AND closest candidate exceeds worst result
            if (currentDist > lowerBound && topCandidates.size() >= ef) {
                break;
            }

            // Explore neighbors
            int[] neighbors = graph.get(current[0])[layer];
            for (int neighborId : neighbors) {
                if (visited.get(neighborId)) continue;
                visited.set(neighborId);

                float dist = distFunc.compute(query, vectors.get(neighborId));

                if (topCandidates.size() < ef || dist < lowerBound) {
                    int distBits = Float.floatToRawIntBits(dist);
                    candidateSet.add(new int[]{neighborId, distBits});
                    topCandidates.add(new int[]{neighborId, distBits});

                    if (topCandidates.size() > ef) {
                        topCandidates.poll();
                    }
                    lowerBound = Float.intBitsToFloat(topCandidates.peek()[1]);
                }
            }
        }

        // Sort results by distance ascending
        List<int[]> result = new ArrayList<>(topCandidates.size());
        result.addAll(topCandidates);
        result.sort((a, b) -> Float.compare(Float.intBitsToFloat(a[1]), Float.intBitsToFloat(b[1])));
        return result;
    }

    private int randomLevel() {
        double r = random.nextDouble();
        if (r < 1e-18) r = 1e-18;
        return (int) (-Math.log(r) * mL);
    }
}
