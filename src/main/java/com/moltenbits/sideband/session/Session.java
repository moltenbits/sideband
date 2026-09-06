package com.moltenbits.sideband.session;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * The client session that currently holds a role: how to reach it and how far into the
 * journal it has read. Nothing else about a role is stored; everything it has done is in
 * the journal. Whoever joins as the role last holds it; no process or conversation is
 * checked against the record.
 *
 * @param id        the host's session identifier (Codex's thread id, which pushes address; Claude Code's session id)
 * @param startedAt when the role joined
 * @param watermark the journal size at activation; entries ending at or before it predate the session
 * @param offset    the read position: entries ending at or before it have been shown to the role
 * @param resumed   joined with --resume: a lone waiting request is acted on, several are confirmed
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Session(
        String id,
        OffsetDateTime startedAt,
        long watermark,
        long offset,
        boolean resumed) {

    Session withOffset(long newOffset) {
        return new Session(id, startedAt, watermark, Math.max(offset, newOffset), resumed);
    }
}
