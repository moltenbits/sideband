package com.moltenbits.sideband.locking;

import java.nio.file.Path;

/**
 * The one lock that serializes every mutation of a repository's Sideband state:
 * journal appends, fragment recovery, and cursor updates.
 */
public interface Locks {

    /** The lock file name inside the state directory. */
    String FILE_NAME = "journal.lock";

    /**
     * Acquires the lock for the state directory, waiting a bounded time for a live owner
     * and reclaiming a lock whose owner is dead.
     *
     * @throws LockTimeoutException when a live owner keeps the lock too long
     */
    Lock acquire(Path stateDirectory);
}
