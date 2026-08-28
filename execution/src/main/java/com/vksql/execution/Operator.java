package com.vksql.execution;

/**
 * A physical operator that produces rows.
 * Pull-based: the parent calls next() to get data.
 *
 * Lifecycle: open() → next() → next() → ... → null → close()
 */
public interface Operator {
    /** Initialize the operator (open files, etc.) */
    void open();

    /** Return the next row, or null if done. */
    Row next();

    /** Release resources. */
    void close();
}
