package com.moltenbits.sideband.locking;

import java.io.IOException;

/** A held exclusive lock; closing releases it. */
public interface Lock extends AutoCloseable {

    @Override
    void close() throws IOException;
}
