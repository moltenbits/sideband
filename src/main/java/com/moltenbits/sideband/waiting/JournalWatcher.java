package com.moltenbits.sideband.waiting;

import com.moltenbits.sideband.journal.Entry;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Predicate;

/** Blocks a caller until complete journal entries matching a filter appear after an offset. */
public interface JournalWatcher {

    /**
     * Waits until at least one complete entry past {@code offset} satisfies {@code filter}.
     * Entries the filter rejects are consumed silently, so the returned read's end offset
     * always moves past them. A null timeout waits without limit.
     */
    Waited await(Path file, long offset, @Nullable Duration timeout, Predicate<Entry> filter);

    default Waited await(Path file, long offset, @Nullable Duration timeout) {
        return await(file, offset, timeout, entry -> true);
    }
}
