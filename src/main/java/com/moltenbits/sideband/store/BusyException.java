package com.moltenbits.sideband.store;

import java.nio.file.Path;
import java.time.Duration;

/** Raised when another process kept the store locked for longer than the busy timeout. */
public class BusyException extends RuntimeException {

    public BusyException(Path database, Duration waited, Throwable cause) {
        super("could not lock " + database + " within " + waited.toMillis() + " ms", cause);
    }
}
