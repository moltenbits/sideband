package com.moltenbits.sideband.protocol;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The machine-readable metadata stored with every journal entry. Field order is the
 * serialized order; the body travels beside it, never inside it. Construction validates the structural rules of the protocol.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record EntryMetadata(
        String id,
        OffsetDateTime createdAt,
        ParticipantId from,
        @Nullable Role via,
        List<ParticipantId> to,
        MessageType type,
        Route route,
        @Nullable String replyTo,
        @Nullable String causedBy,
        boolean expectsReply,
        Delivery delivery) {

    public EntryMetadata {
        Rules.checkId(id, "id");
        Objects.requireNonNull(createdAt, "created_at");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(delivery, "delivery");
        to = to == null ? List.of() : List.copyOf(to);
        Rules.check(from, via, to, type, route, replyTo, expectsReply);
        if (replyTo != null) {
            Rules.checkId(replyTo, "reply_to");
        }
        if (causedBy != null) {
            Rules.checkId(causedBy, "caused_by");
        }
    }

    /** True when the author is a client rather than a human. */
    public boolean isAgentAuthored() {
        return !from.isHuman();
    }

    /** True when at least one recipient is a client role. */
    public boolean addressesAnyClient() {
        return to.stream().anyMatch(id -> !id.isHuman());
    }

    public boolean addresses(ParticipantId participant) {
        return to.contains(participant);
    }
}
