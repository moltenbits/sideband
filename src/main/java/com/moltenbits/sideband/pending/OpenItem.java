package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.handoff.Handoff;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * An actionable entry the role has not answered. {@code beforeSession} marks one that was
 * already in the journal when a fresh session started, which the operator confirms before
 * it is acted on; a session joined with {@code --resume} asked for those, so it never sets
 * the flag. {@code acknowledgedAt} is the role's latest ack, so a cleared context can see
 * what it had already taken up.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record OpenItem(Handoff entry, boolean beforeSession, @Nullable OffsetDateTime acknowledgedAt) {
}
