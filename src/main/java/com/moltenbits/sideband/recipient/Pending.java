package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.journal.Entry;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;
import java.util.Map;

/**
 * Everything a role still has open: incoming entries not yet resolved, split into backlog
 * (at or before the watermark) and live, and outgoing requests still awaiting an answer.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Pending(List<Entry> backlog, List<Entry> live, Map<String, OutgoingState> outgoing) {
}
