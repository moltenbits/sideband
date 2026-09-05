package com.moltenbits.sideband.recipient;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * What this client has done with one incoming entry. Each field is a separate fact:
 * seen (summarized or shown), delivered (handed to the host's wake mechanism), resolved.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record EntryState(
        @Nullable OffsetDateTime seenAt,
        @Nullable OffsetDateTime deliveredAt,
        @Nullable OffsetDateTime resolvedAt,
        @Nullable Resolution resolution) {

    public static final EntryState NONE = new EntryState(null, null, null, null);

    public boolean isResolved() {
        return resolvedAt != null;
    }

    EntryState seen(OffsetDateTime at) {
        return seenAt == null ? new EntryState(at, deliveredAt, resolvedAt, resolution) : this;
    }

    EntryState delivered(OffsetDateTime at) {
        return new EntryState(seenAt == null ? at : seenAt, at, resolvedAt, resolution);
    }

    EntryState resolved(OffsetDateTime at, Resolution how) {
        return new EntryState(seenAt == null ? at : seenAt, deliveredAt, at, how);
    }
}
