package com.moltenbits.sideband.recipient;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * The client session that currently owns this role's listener.
 *
 * @param id           the host's session identifier
 * @param startedAt    when Sideband was activated for it
 * @param parentPid    the host process, when known, so a dead session can be superseded
 * @param watermarkId  the last complete entry at activation, or null for an empty journal
 * @param watermarkEnd the byte offset just past that entry; later entries are live
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Session(
        String id,
        OffsetDateTime startedAt,
        @Nullable Long parentPid,
        @Nullable String watermarkId,
        long watermarkEnd) {

    /** True when the recorded host process is known to be alive. Unknown counts as alive. */
    public boolean isLive() {
        if (parentPid == null) {
            return true;
        }
        return ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
    }
}
