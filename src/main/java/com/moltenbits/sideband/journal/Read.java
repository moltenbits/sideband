package com.moltenbits.sideband.journal;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * Complete entries read from a journal.
 *
 * @param start   the offset the read began at
 * @param end     the offset just past the last complete entry; the next read should start here
 * @param entries the bodies of the complete entries, in physical order
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Read(long start, long end, List<String> entries) {

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
