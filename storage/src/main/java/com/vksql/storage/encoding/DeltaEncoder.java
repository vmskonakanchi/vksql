package com.vksql.storage.encoding;

/**
 * Delta encoding for long columns with monotonically increasing values.
 * Stores the first value as base and successive differences as deltas.
 * Example: [1000, 1001, 1003, 1006] → base=1000, deltas=[0, 1, 2, 3]
 */
public final class DeltaEncoder {

    private DeltaEncoder() {}

    /**
     * Encodes a long array using delta encoding.
     * The first delta is always 0 (value[0] - baseValue = 0).
     * Subsequent deltas are differences between consecutive values.
     *
     * @param values the raw long column values (should be monotonically increasing)
     * @return a DeltaEncoded record with baseValue and deltas
     */
    public static DeltaEncoded encode(long[] values) {
        if (values.length == 0) {
            return new DeltaEncoded(0, new int[0]);
        }

        long baseValue = values[0];
        int[] deltas = new int[values.length];
        deltas[0] = 0;

        for (int i = 1; i < values.length; i++) {
            deltas[i] = (int) (values[i] - values[i - 1]);
        }

        return new DeltaEncoded(baseValue, deltas);
    }

    /**
     * Decodes a delta-encoded column back to the original long values.
     *
     * @param baseValue the first value in the sequence
     * @param deltas    the successive differences between consecutive values
     * @return the reconstructed long column
     */
    public static long[] decode(long baseValue, int[] deltas) {
        if (deltas.length == 0) {
            return new long[0];
        }

        long[] result = new long[deltas.length];
        result[0] = baseValue;

        for (int i = 1; i < deltas.length; i++) {
            result[i] = result[i - 1] + deltas[i];
        }

        return result;
    }
}
