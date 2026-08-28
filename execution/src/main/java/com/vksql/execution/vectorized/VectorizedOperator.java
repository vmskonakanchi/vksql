package com.vksql.execution.vectorized;

/**
 * Same as Operator but returns batches instead of single rows.
 * next() returns a RecordBatch of up to 1024 rows, or null when done.
 */
public interface VectorizedOperator {
    void open();
    RecordBatch next();  // returns batch of rows, null when done
    void close();
}
