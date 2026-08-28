package com.vksql.storage.encoding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dictionary encoding for String columns.
 * Replaces string values with integer indices into a compact dictionary.
 * Most effective for low-cardinality columns (< 40% unique values).
 */
public final class DictionaryEncoder {

    private DictionaryEncoder() {}

    /**
     * Encodes a String column using dictionary encoding.
     * The dictionary preserves insertion order of first occurrence.
     *
     * @param values the raw string column values
     * @return a DictionaryEncoded record with the dictionary and index array
     */
    public static DictionaryEncoded encode(String[] values) {
        Map<String, Integer> dictMap = new LinkedHashMap<>();
        int[] indices = new int[values.length];

        for (int i = 0; i < values.length; i++) {
            String val = values[i];
            Integer idx = dictMap.get(val);
            if (idx == null) {
                idx = dictMap.size();
                dictMap.put(val, idx);
            }
            indices[i] = idx;
        }

        String[] dictionary = dictMap.keySet().toArray(new String[0]);
        return new DictionaryEncoded(dictionary, indices);
    }

    /**
     * Decodes a dictionary-encoded column back to the original String values.
     *
     * @param dictionary the unique value dictionary
     * @param indices    the index array referencing the dictionary
     * @return the reconstructed String column
     */
    public static String[] decode(String[] dictionary, int[] indices) {
        String[] result = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            result[i] = dictionary[indices[i]];
        }
        return result;
    }
}
