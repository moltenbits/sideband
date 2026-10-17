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
        @Nullable ParticipantId via,
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

    /**
     * True when an agent's hook recorded what it said in its own terminal: exactly the shape
     * {@link Draft#capturedTurn} writes, a status from the instance, via itself, to the
     * operator alone, asking nothing. {@code via} naming the author is not enough on its
     * own: an agent entry may carry it, and one that does is still a message to whoever
     * it addresses.
     */
    public boolean isCapturedTurn() {
        return isAgentAuthored() && from.equals(via) && type == MessageType.STATUS && !expectsReply
                && to.equals(List.of(ParticipantId.OPERATOR));
    }

    /**
     * The entry this one came from: {@code caused_by} when it is set, since that names the work it
     * serves, otherwise the entry it replies to; null for an entry with neither.
     */
    public @Nullable String cause() {
        return causedBy != null ? causedBy : replyTo;
    }

    /** True when at least one recipient is a client role. */
    public boolean addressesAnyClient() {
        return to.stream().anyMatch(id -> !id.isHuman());
    }

    public boolean addresses(ParticipantId participant) {
        return to.contains(participant);
    }
}
