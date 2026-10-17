package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code captured_turns} table: a turn's receipt. */
@MappedEntity("captured_turns")
record CapturedTurnRow(
        @Id @GeneratedValue @Nullable Long id,
        String role,
        String sessionId,
        String turnId,
        @Nullable String promptEntryId,
        String mode,
        boolean finalCaptured) {
}
