package com.moltenbits.sideband.waiting;

import com.moltenbits.sideband.journal.Entry;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Predicate;

/** Blocks a caller until journal entries matching a filter appear after a position. */
public interface JournalWatcher {

    /**
     * Waits until at least one entry past {@code position} satisfies {@code filter}.
     * Entries the filter rejects are consumed silently, so the returned read's end
     * always moves past them. A null timeout waits without limit.
     */
    Waited await(Path stateDirectory, long position, @Nullable Duration timeout, Predicate<Entry> filter);

    default Waited await(Path stateDirectory, long position, @Nullable Duration timeout) {
        return await(stateDirectory, position, timeout, entry -> true);
    }
}
