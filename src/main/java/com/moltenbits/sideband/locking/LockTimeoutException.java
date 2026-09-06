package com.moltenbits.sideband.locking;

import java.nio.file.Path;
import java.time.Duration;

/** Raised when the shared lock could not be acquired before the timeout elapsed. */
public class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(Path lockFile, Duration waited) {
        super("could not acquire " + lockFile + " within " + waited.toMillis() + " ms");
    }
}
