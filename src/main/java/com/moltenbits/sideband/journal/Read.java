package com.moltenbits.sideband.journal;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * The result of reading entries after a position.
 *
 * @param start   the position the read began after
 * @param end     the position of the last entry read, or {@code start} when there was none; the next read starts after it
 * @param entries the entries in order
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Read(long start, long end, List<Entry> entries) {

    public Read {
        entries = List.copyOf(entries);
    }

    public static Read empty(long position) {
        return new Read(position, position, List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
