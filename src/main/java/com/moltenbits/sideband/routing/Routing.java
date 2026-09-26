package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.ParticipantId;

import java.util.Set;

/** Resolves where a human's message goes from a directive at the start of its body. */
public interface Routing {

    /**
     * Reads the first non-whitespace token of {@code body}, case-insensitively.
     * {@code @claude} and {@code @codex} select the unnamed instance, {@code @claude:<name>}
     * that named instance, and {@code @all} both unnamed instances and every instance in
     * {@code joined}; anything else routes to {@code via} alone (REQUIREMENTS.md 8.1). The
     * body is never altered.
     *
     * @param joined the instances that have a session record, for {@code @all}
     */
    Destination resolve(String body, ParticipantId via, Set<ParticipantId> joined);
}
