package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

/**
 * One row of the {@code entries} table: the metadata as columns, the body as text, and the
 * position the database assigns on insert. Timestamps are RFC 3339 text and recipients a
 * comma-separated list, since participant identifiers never contain a comma.
 */
@MappedEntity("entries")
record EntryRow(
        @Id @GeneratedValue @Nullable Long seq,
        @MappedProperty("id") String messageId,
        String createdAt,
        String sender,
        @Nullable String via,
        String recipients,
        String type,
        String route,
        @Nullable String replyTo,
        @Nullable String causedBy,
        boolean expectsReply,
        String live,
        String backlog,
        String body) {
}
