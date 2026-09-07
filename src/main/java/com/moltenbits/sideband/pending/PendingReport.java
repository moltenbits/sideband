package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.handoff.Handoff;
import com.moltenbits.sideband.journal.Diagnostic;
import com.moltenbits.sideband.session.Session;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * Everything a role has to look at, derived from the journal: requests it has not answered
 * ({@code open} before any ack, {@code inProgress} after one), informational entries it has
 * not been shown ({@code updates}), and its own requests still awaiting a reply.
 * {@code end} is the journal position the report covers.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record PendingReport(
        String intent,
        @Nullable Session session,
        List<OpenItem> open,
        List<OpenItem> inProgress,
        List<Handoff> updates,
        List<OutgoingReport> outgoing,
        List<Diagnostic> diagnostics,
        long end) {

    public int waiting() {
        return open.size() + updates.size();
    }

    /**
     * Everything the role still has to act on, counting the requests it acknowledged and
     * has not yet answered as well: what a conversation that lost its context, as after a
     * clear, must be told about, since only its memory of the ack was lost.
     */
    public int unfinished() {
        return waiting() + inProgress.size();
    }
}
