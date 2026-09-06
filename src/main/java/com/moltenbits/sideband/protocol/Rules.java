package com.moltenbits.sideband.protocol;

import io.micronaut.core.annotation.Nullable;

import java.util.List;

/** The structural rules shared by drafts and stored metadata. */
final class Rules {

    private Rules() {
    }

    static void check(ParticipantId from, @Nullable Role via, List<ParticipantId> to, MessageType type,
                      Route route, @Nullable String replyTo, boolean expectsReply) {
        if (to == null || to.isEmpty()) {
            throw new InvalidEntryException("'to' must name at least one recipient");
        }
        if (to.stream().distinct().count() != to.size()) {
            throw new InvalidEntryException("'to' must not repeat a recipient");
        }
        if (from.isHuman() && via == null) {
            throw new InvalidEntryException("'via' is required when the author is a human");
        }
        if ((type == MessageType.REPLY || type == MessageType.ACK) && replyTo == null) {
            throw new InvalidEntryException("a " + type.id() + " must set 'reply_to'");
        }
        if (type == MessageType.ACK && expectsReply) {
            throw new InvalidEntryException("an ack never expects a reply");
        }
        Route expected = Route.forRecipients(to);
        if (route != expected) {
            throw new InvalidEntryException("'route' must be " + expected.name().toLowerCase()
                    + " for " + to.size() + " recipient" + (to.size() == 1 ? "" : "s"));
        }
    }

    static void checkId(String id, String field) {
        if (id == null || id.isBlank()) {
            throw new InvalidEntryException("'" + field + "' must not be blank");
        }
        if (id.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidEntryException("'" + field + "' must not contain whitespace");
        }
    }
}
