package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code captured_messages} table: a message recorded from a session's transcript, by its host's identity. */
@MappedEntity("captured_messages")
record CapturedMessageRow(
        @Id @GeneratedValue @Nullable Long id,
        String role,
        String sessionId,
        String messageId) {
}
