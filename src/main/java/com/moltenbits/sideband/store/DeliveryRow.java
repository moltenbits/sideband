package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code deliveries} table: a push of one entry to one role's session that the host accepted. */
@MappedEntity("deliveries")
record DeliveryRow(
        @Id @GeneratedValue @Nullable Long id,
        long seq,
        String role,
        String sessionId,
        String pushedAt) {
}
