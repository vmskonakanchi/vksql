package com.vksql.vector;

/**
 * A column of fixed-dimension float vectors stored as a dense float[][] array.
 * Each row is a vector of the same dimensionality.
 */
public final class VectorColumn {

    private final int dimension;
    private final float[][] vectors;

    /**
     * Creates a vector column.
     *
     * @param dimension the dimensionality of each vector
     * @param vectors   the array of vectors (each must have length == dimension)
     */
    public VectorColumn(int dimension, float[][] vectors) {
        this.dimension = dimension;
        this.vectors = vectors;
    }

    /** Returns the dimensionality of vectors in this column. */
    public int dimension() {
        return dimension;
    }

    /** Returns the number of vectors stored. */
    public int size() {
        return vectors.length;
    }

    /** Returns the vector at the given index. */
    public float[] get(int index) {
        return vectors[index];
    }

    /** Returns the raw underlying float[][] array. */
    public float[][] raw() {
        return vectors;
    }
}
