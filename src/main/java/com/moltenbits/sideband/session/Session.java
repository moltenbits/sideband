package com.moltenbits.sideband.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moltenbits.sideband.host.HostProcess;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * The client session that currently holds an instance: how to reach it and how far into the
 * journal it has read. Nothing else about an instance is stored; everything it has done is
 * in the journal. Whoever joins under an identifier last holds it; the process is never used
 * to refuse anything, only to tell which instance a calling session is (REQUIREMENTS.md 9.5a).
 *
 * @param id        the host's session identifier (Codex's thread id, which pushes address; Claude Code's session id)
 * @param startedAt when the instance joined
 * @param watermark the journal size at activation; entries ending at or before it predate the session
 * @param offset    the read position: entries ending at or before it have been shown to the instance
 * @param resumed   joined with --resume: a lone waiting request is acted on, several are confirmed
 * @param process   the host process, when the host names one and no later record has taken it
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Session(
        String id,
        OffsetDateTime startedAt,
        long watermark,
        long offset,
        boolean resumed,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable HostProcess process) {

    public Session withOffset(long newOffset) {
        return new Session(id, startedAt, watermark, Math.max(offset, newOffset), resumed, process);
    }

    /** The same record at another host session, as after a clear or a restart. */
    public Session at(String newId, @Nullable HostProcess newProcess) {
        return new Session(newId, startedAt, watermark, offset, resumed, newProcess);
    }

    public Session withProcess(@Nullable HostProcess newProcess) {
        return new Session(id, startedAt, watermark, offset, resumed, newProcess);
    }
}
