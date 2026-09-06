package com.moltenbits.sideband.protocol;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Everything a writer supplies for a new entry. The journal assigns the identifier,
 * timestamp, and body length when it appends the draft.
 */
public record Draft(
        ParticipantId from,
        @Nullable Role via,
        List<ParticipantId> to,
        MessageType type,
        Route route,
        @Nullable String replyTo,
        @Nullable String causedBy,
        boolean expectsReply,
        Delivery delivery,
        String body) {

    public Draft {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(body, "body");
        to = to == null ? List.of() : List.copyOf(to);
        Rules.check(from, via, to, type, route, replyTo, expectsReply);
        if (replyTo != null) {
            Rules.checkId(replyTo, "reply_to");
        }
        if (causedBy != null) {
            Rules.checkId(causedBy, "caused_by");
        }
        if (body.isBlank()) {
            throw new InvalidEntryException("the body must not be blank");
        }
    }

    /** A human's direct prompt, routed to {@code to}, entered through {@code via}. */
    /** What a human typed: a request whoever it is addressed to, and the root of every chain. */
    public static Draft humanRequest(ParticipantId human, Role via, List<ParticipantId> to, String body) {
        return new Draft(human, via, to, MessageType.REQUEST, Route.forRecipients(to),
                null, null, true, Delivery.DEFAULT, body);
    }
}
