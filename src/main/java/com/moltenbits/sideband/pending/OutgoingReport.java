package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A request the role sent that no recipient has replied to yet: what the recipient has
 * said about it (acks) and how long it has been silent. The sender decides what to do.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record OutgoingReport(
        String id,
        List<ParticipantId> to,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime acknowledgedAt,
        List<String> ackIds,
        long silenceSeconds) {
}
