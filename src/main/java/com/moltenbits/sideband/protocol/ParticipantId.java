package com.moltenbits.sideband.protocol;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Identifies a participant: a client role such as {@code claude} or {@code codex},
 * or a human such as {@code human:james}. Serialized as its plain string.
 */
public record ParticipantId(String value) implements Comparable<ParticipantId> {

    public static final String HUMAN_PREFIX = "human:";

    private static final Pattern HUMAN = Pattern.compile("human:[a-z0-9][a-z0-9._-]*");

    public ParticipantId {
        Objects.requireNonNull(value, "value");
        if (Role.fromId(value).isEmpty() && !HUMAN.matcher(value).matches()) {
            throw new InvalidEntryException("'" + value + "' is not a participant: expected claude, codex, or human:<id>");
        }
    }

    public static ParticipantId of(Role role) {
        return new ParticipantId(role.id());
    }

    public static ParticipantId human(String id) {
        return new ParticipantId(HUMAN_PREFIX + id);
    }

    public boolean isHuman() {
        return value.startsWith(HUMAN_PREFIX);
    }

    /** The client role, when this participant is one. */
    public Optional<Role> role() {
        return Role.fromId(value);
    }

    /** A presentation name for headings: the role's name, or the human id with its first letter capitalized. */
    public String displayName() {
        return role().map(Role::displayName).orElseGet(() -> {
            String id = value.substring(HUMAN_PREFIX.length());
            return Character.toUpperCase(id.charAt(0)) + id.substring(1);
        });
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
