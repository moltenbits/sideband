package com.moltenbits.sideband.protocol;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Identifies a participant: a client instance or the one human, {@code operator}. A client
 * instance is its role, {@code claude} or {@code codex}, for the unnamed instance, or
 * {@code <role>:<name>} for a named one, such as {@code claude:fable}. Serialized as its
 * plain string, so the version-one role names read as the unnamed instances. There is
 * exactly one human per journal, so nothing about who they are is configured or recorded.
 */
public record ParticipantId(String value) implements Comparable<ParticipantId> {

    public static final String OPERATOR_ID = "operator";
    public static final ParticipantId OPERATOR = new ParticipantId(OPERATOR_ID);

    /** What a name may be: safe in a routing directive, a hook's context field, and Claude Code's {@code from-name}. */
    public static final String NAME_RULE = "a lowercase letter followed by up to 31 lowercase letters, digits, and hyphens";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern INSTANCE = Pattern.compile("([a-z]+)(?::(.*))?", Pattern.DOTALL);

    public ParticipantId {
        Objects.requireNonNull(value, "value");
        if (!OPERATOR_ID.equals(value) && parse(value).isEmpty()) {
            String roles = Arrays.stream(Role.values()).map(Role::id).collect(Collectors.joining("|"));
            throw new InvalidEntryException("'" + value + "' is not a participant: expected " + OPERATOR_ID + ", <" + roles
                    + ">, or <" + roles + ">:<name>, where a name is " + NAME_RULE);
        }
    }

    public static ParticipantId of(Role role) {
        return new ParticipantId(role.id());
    }

    /** The named instance of the role. */
    public static ParticipantId of(Role role, String name) {
        Objects.requireNonNull(name, "name");
        return new ParticipantId(role.id() + ":" + name);
    }

    public boolean isHuman() {
        return OPERATOR_ID.equals(value);
    }

    /** The client role, when this participant is a client instance. */
    public Optional<Role> role() {
        return parse(value).map(Instance::role);
    }

    /** The instance's name, when this participant is a named instance. */
    public Optional<String> name() {
        return parse(value).flatMap(Instance::name);
    }

    /** True for a role's unnamed instance, the bare role. */
    public boolean isUnnamed() {
        return role().isPresent() && name().isEmpty();
    }

    /** A presentation name for headings and attribution: {@code Claude}, {@code Claude (fable)}, {@code Operator}. */
    public String displayName() {
        return parse(value)
                .map(instance -> instance.role().displayName() + instance.name().map(name -> " (" + name + ")").orElse(""))
                .orElse("Operator");
    }

    @Override
    public int compareTo(ParticipantId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static Optional<Instance> parse(String value) {
        Matcher matcher = INSTANCE.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Optional<Role> role = Role.fromId(matcher.group(1));
        String name = matcher.group(2);
        if (role.isEmpty() || (name != null && !NAME.matcher(name).matches())) {
            return Optional.empty();
        }
        return Optional.of(new Instance(role.get(), Optional.ofNullable(name)));
    }

    private record Instance(Role role, Optional<String> name) {
    }
}
