package com.moltenbits.sideband.waiting;

import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Polls the journal tail at a modest interval. Reading from the caller's offset keeps
 * each poll proportional to new bytes, not to the journal's size.
 */
@Singleton
class PollingWatcher implements JournalWatcher {

    static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final Journal journal;

    PollingWatcher(Journal journal) {
        this.journal = journal;
    }

    @Override
    public Read await(Path file, long offset) {
        return poll(file, offset, null).orElseThrow();
    }

    @Override
    public Optional<Read> await(Path file, long offset, Duration timeout) {
        return poll(file, offset, Instant.now().plus(timeout));
    }

    private Optional<Read> poll(Path file, long offset, Instant deadline) {
        while (true) {
            Read read = journal.readCompleteFrom(file, offset);
            if (!read.isEmpty()) {
                return Optional.of(read);
            }
            if (deadline != null && !Instant.now().isBefore(deadline)) {
                return Optional.empty();
            }
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the journal", e);
        }
    }
}
