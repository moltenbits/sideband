package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A request the role sent that no recipient has replied to yet: what the recipient has
 * said about it (acks), how long it has been silent, and whether that silence exceeds the
 * heartbeat the request asked for. The sender decides what to do about it.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record OutgoingReport(
        String id,
        List<ParticipantId> to,
        OffsetDateTime createdAt,
        @Nullable Long heartbeatSeconds,
        @Nullable OffsetDateTime acknowledgedAt,
        List<String> ackIds,
        long silenceSeconds,
        boolean overdue) {
}
