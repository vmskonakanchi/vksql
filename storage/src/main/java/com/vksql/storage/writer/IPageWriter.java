package com.vksql.storage.writer;

import com.vksql.storage.format.Page;

public interface IPageWriter {
    Page flush();

    boolean isFull();

    boolean hasData();

    void writeNull();
}
