package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.journal.Diagnostic;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * A set of handoffs from one journal read: the entries, skipped regions, the byte range
 * scanned, and whether a bounded wait gave up. This is the JSON shape a listener prints
 * and the JSON shape inside a pushed envelope.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Batch(long start, long end, List<Handoff> entries, List<Diagnostic> diagnostics, boolean timedOut) {

    public static Batch timedOut(long offset, List<Diagnostic> diagnostics) {
        return new Batch(offset, offset, List.of(), diagnostics, true);
    }
}
