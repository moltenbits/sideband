package com.moltenbits.sideband.journal;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * The result of reading complete entries.
 *
 * @param start       the offset the read began at
 * @param end         the offset just past the last consumed byte; the next read should start here
 * @param entries     well-formed entries in physical order
 * @param diagnostics malformed regions that were skipped
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Read(long start, long end, List<Entry> entries, List<Diagnostic> diagnostics) {

    public Read {
        entries = List.copyOf(entries);
        diagnostics = List.copyOf(diagnostics);
    }

    public static Read empty(long offset) {
        return new Read(offset, offset, List.of(), List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
