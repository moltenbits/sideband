package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code sessions} table: an instance's record, keyed by the participant, naming the client it runs in. */
@MappedEntity("sessions")
record SessionRow(
        @Id String participant,
        String client,
        String sessionId,
        String startedAt,
        long watermark,
        long bookmark,
        boolean resumed,
        @Nullable Long hostPid,
        @Nullable String hostStartedAt) {
}
