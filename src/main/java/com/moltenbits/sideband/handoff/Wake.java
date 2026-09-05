package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.journal.Diagnostic;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * One line of listener output: a wake signal, not the payload. Hosts truncate a
 * notification to a few hundred characters, so this carries only counts, the senders,
 * the byte range scanned, and where to read the entries. The handling text comes first.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Wake(String handling, long start, long end, int entries, int actionable, List<String> from, int diagnostics) {

    public static Wake of(Role role, long start, long end, List<Entry> entries, List<Diagnostic> diagnostics) {
        List<String> from = entries.stream().map(e -> e.metadata().from().toString()).distinct().toList();
        int actionable = (int) entries.stream().filter(e -> e.metadata().expectsReply()).count();
        return new Wake(Handling.wake(role), start, end, entries.size(), actionable, from, diagnostics.size());
    }
}
