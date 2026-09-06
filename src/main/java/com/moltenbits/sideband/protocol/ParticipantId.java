package com.moltenbits.sideband.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies a participant: a client role, {@code claude} or {@code codex}, or the one human,
 * {@code operator}. Serialized as its plain string. There is exactly one human per journal,
 * so nothing about who they are is configured or recorded.
 */
public record ParticipantId(String value) implements Comparable<ParticipantId> {

    public static final String OPERATOR_ID = "operator";
    public static final ParticipantId OPERATOR = new ParticipantId(OPERATOR_ID);

    public ParticipantId {
        Objects.requireNonNull(value, "value");
        if (Role.fromId(value).isEmpty() && !OPERATOR_ID.equals(value)) {
            throw new InvalidEntryException("'" + value + "' is not a participant: expected claude, codex, or operator");
        }
    }

    public static ParticipantId of(Role role) {
        return new ParticipantId(role.id());
    }

    public boolean isHuman() {
        return OPERATOR_ID.equals(value);
    }

    /** The client role, when this participant is one. */
    public Optional<Role> role() {
        return Role.fromId(value);
    }

    /** A presentation name for headings. */
    public String displayName() {
        return role().map(Role::displayName).orElse("Operator");
    }

    @Override
    public int compareTo(ParticipantId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
