package com.moltenbits.sideband.store;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code sessions} table: the role's record, keyed by the role. */
@MappedEntity("sessions")
record SessionRow(
        @Id String role,
        String sessionId,
        String startedAt,
        long watermark,
        long bookmark,
        boolean resumed) {
}
