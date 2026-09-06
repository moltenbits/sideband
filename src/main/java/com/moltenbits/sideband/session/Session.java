package com.moltenbits.sideband.session;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * The client session that currently owns a role: who it is and how far into the journal
 * it has read. Nothing else about a role is stored; everything it has done is in the journal.
 *
 * @param id        the host's session identifier (Codex's thread id, Claude Code's session id)
 * @param startedAt when Sideband was activated for it
 * @param parentPid the host process, when known, so a dead session can be superseded
 * @param watermark the journal size at activation; entries ending at or before it predate the session
 * @param offset    the read position: entries ending at or before it have been shown to the role
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Session(
        String id,
        OffsetDateTime startedAt,
        @Nullable Long parentPid,
        long watermark,
        long offset) {

    /** True when the recorded host process is known to be alive. Unknown counts as alive. */
    public boolean isLive() {
        if (parentPid == null) {
            return true;
        }
        return ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
    }

    Session withOffset(long newOffset) {
        return new Session(id, startedAt, parentPid, watermark, Math.max(offset, newOffset));
    }

    Session withId(String newId) {
        return new Session(newId, startedAt, parentPid, watermark, offset);
    }

    Session withParentPid(Long pid) {
        return new Session(id, startedAt, pid, watermark, offset);
    }
}
