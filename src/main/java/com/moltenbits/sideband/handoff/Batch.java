package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.journal.Diagnostic;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * A set of handoffs from one journal read: the entries, skipped regions, the byte range
 * scanned, and whether a bounded wait gave up. This is the JSON shape a listener prints
 * and the JSON shape inside a pushed envelope. {@code intent} comes first so a host
 * reads what the batch is and how to act on it before the data; it is absent only when
 * the read was for no role at all.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Batch(@Nullable String intent, long start, long end, List<Handoff> entries, List<Diagnostic> diagnostics,
                    boolean timedOut) {

    public static Batch forRole(@Nullable Role role, long start, long end, List<Handoff> entries, List<Diagnostic> diagnostics,
                                boolean timedOut) {
        return new Batch(role == null ? null : Handling.forRole(role), start, end, entries, diagnostics, timedOut);
    }
}
