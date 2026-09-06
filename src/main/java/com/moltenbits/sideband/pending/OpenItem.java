package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.handoff.Handoff;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * An actionable entry the role has not answered. {@code beforeSession} marks one that was
 * already in the journal when the session started and needs the operator's confirmation:
 * always after a plain join, and after {@code --resume} only when more than one request is
 * waiting, since a lone one is acted on. {@code acknowledgedAt} is the role's latest ack,
 * so a cleared context can see what it had already taken up.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record OpenItem(Handoff entry, boolean beforeSession, @Nullable OffsetDateTime acknowledgedAt) {
}
