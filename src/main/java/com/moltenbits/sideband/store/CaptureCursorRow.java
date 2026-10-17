package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code capture_cursors} table: a session's place in its transcript and the version of its capture state. */
@MappedEntity("capture_cursors")
record CaptureCursorRow(
        @Id @GeneratedValue @Nullable Long id,
        String role,
        String sessionId,
        @Nullable String transcript,
        @Nullable Long position,
        @Nullable String turnId,
        long version) {
}
