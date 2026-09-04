package com.moltenbits.sideband.waiting;

import com.moltenbits.sideband.journal.Read;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Blocks a caller until complete journal entries appear after an offset. */
public interface JournalWatcher {

    /** Waits without limit; returns as soon as at least one complete entry exists past {@code offset}. */
    Read await(Path file, long offset);

    /** Waits at most {@code timeout}; empty when it elapses with no complete entry past {@code offset}. */
    Optional<Read> await(Path file, long offset, Duration timeout);
}
