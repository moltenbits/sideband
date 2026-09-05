package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.journal.Diagnostic;
import com.moltenbits.sideband.journal.Entry;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * What activating a session established: the watermark to start the listener from, the
 * backlog awaiting the human's decision, and anything the journal scan had to skip.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Activation(Session session, List<Entry> backlog, List<Diagnostic> diagnostics) {
}
