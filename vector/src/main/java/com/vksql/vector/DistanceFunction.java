package com.vksql.vector;

/**
 * Distance functions for vector similarity search.
 * All functions return a distance where smaller = more similar.
 */
public enum DistanceFunction {

    /** Euclidean (L2) distance: sqrt(sum((a[i]-b[i])^2)) */
    L2 {
        @Override
        public float compute(float[] a, float[] b) {
            float sum = 0.0f;
            for (int i = 0; i < a.length; i++) {
                float diff = a[i] - b[i];
                sum += diff * diff;
            }
            return (float) Math.sqrt(sum);
        }
    },

    /** Cosine distance: 1 - (dot(a,b) / (norm(a) * norm(b))) */
    COSINE {
        @Override
        public float compute(float[] a, float[] b) {
            float dot = 0.0f;
            float normA = 0.0f;
            float normB = 0.0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            return 1.0f - dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
        }
    },

    /** Negative dot product: -dot(a,b) so that smaller = more similar */
    DOT_PRODUCT {
        @Override
        public float compute(float[] a, float[] b) {
            float dot = 0.0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
            }
            return -dot;
        }
    };

    /**
     * Computes the distance between two vectors.
     *
     * @param a first vector
     * @param b second vector
     * @return distance (smaller = more similar)
     */
    public abstract float compute(float[] a, float[] b);
}
