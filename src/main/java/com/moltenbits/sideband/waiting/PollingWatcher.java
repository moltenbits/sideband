package com.moltenbits.sideband.waiting;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * Polls the journal at a modest interval. Reading from the caller's position keeps each
 * poll proportional to new entries, not to the journal's size.
 */
@Singleton
class PollingWatcher implements JournalWatcher {

    static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final Journal journal;

    PollingWatcher(Journal journal) {
        this.journal = journal;
    }

    @Override
    public Waited await(Path stateDirectory, long position, @Nullable Duration timeout, Predicate<Entry> filter) {
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        long start = position;
        long current = position;
        while (true) {
            Read read = journal.readAfter(stateDirectory, current);
            List<Entry> matching = read.entries().stream().filter(filter).toList();
            if (!matching.isEmpty()) {
                return new Waited(new Read(start, read.end(), matching), false);
            }
            current = read.end();
            if (deadline != null && !Instant.now().isBefore(deadline)) {
                return new Waited(new Read(start, current, List.of()), true);
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
