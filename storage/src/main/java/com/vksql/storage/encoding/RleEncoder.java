package com.vksql.storage.encoding;

import java.util.Arrays;

/**
 * Run-Length Encoding for int columns with repeated/sorted values.
 * Replaces consecutive runs of the same value with (value, count) pairs.
 * Example: [1,1,1,2,2,3] → values=[1,2,3], runLengths=[3,2,1]
 */
public final class RleEncoder {

    private RleEncoder() {}

    /**
     * Encodes an int array using run-length encoding.
     *
     * @param input the raw int column values
     * @return an RleEncoded record with values and their run lengths
     */
    public static RleEncoded encode(int[] input) {
        if (input.length == 0) {
            return new RleEncoded(new int[0], new int[0]);
        }

        // First pass: count distinct runs
        int runCount = 1;
        for (int i = 1; i < input.length; i++) {
            if (input[i] != input[i - 1]) {
                runCount++;
            }
        }

        int[] values = new int[runCount];
        int[] runLengths = new int[runCount];

        int runIdx = 0;
        values[0] = input[0];
        runLengths[0] = 1;

        for (int i = 1; i < input.length; i++) {
            if (input[i] == input[i - 1]) {
                runLengths[runIdx]++;
            } else {
                runIdx++;
                values[runIdx] = input[i];
                runLengths[runIdx] = 1;
            }
        }

        return new RleEncoded(values, runLengths);
    }

    /**
     * Decodes a run-length encoded column back to the original int values.
     *
     * @param values     the distinct run values
     * @param runLengths the length of each run
     * @return the reconstructed int column
     */
    public static int[] decode(int[] values, int[] runLengths) {
        int totalLength = 0;
        for (int len : runLengths) {
            totalLength += len;
        }

        int[] result = new int[totalLength];
        int pos = 0;
        for (int i = 0; i < values.length; i++) {
            Arrays.fill(result, pos, pos + runLengths[i], values[i]);
            pos += runLengths[i];
        }

        return result;
    }
}
