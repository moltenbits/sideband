package com.moltenbits.sideband.protocol;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies a participant: an agent, {@code <project>/<name>} such as {@code sideband/astra},
 * or the one human, {@code operator}. The project is the registered name of the project the
 * agent joined in, so the same name in two projects is two participants. Which client an
 * agent runs in is not part of its identity; its session record says. Serialized as its
 * plain string. There is exactly one human per journal, so nothing about who they are is
 * configured or recorded.
 */
public record ParticipantId(String value) implements Comparable<ParticipantId> {

    public static final String OPERATOR_ID = "operator";
    public static final ParticipantId OPERATOR = new ParticipantId(OPERATOR_ID);

    /** What a name may be: safe in a routing directive, a hook's context field, and Claude Code's {@code from-name}. */
    public static final String NAME_RULE = "a lowercase letter followed by up to 31 lowercase letters, digits, and hyphens";

    /** The longest name, and the longest project name. */
    public static final int NAME_LENGTH = 32;

    /** Words no agent may take as its name: the broadcast directive's and the human's. */
    public static final Set<String> RESERVED = Set.of("all", OPERATOR_ID);

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern AGENT = Pattern.compile("([^/]*)/([^/]*)", Pattern.DOTALL);
    private static final Pattern ADDRESS = Pattern.compile("(?:([^/@]*)/)?@?([^/@]*)", Pattern.DOTALL);

    public ParticipantId {
        Objects.requireNonNull(value, "value");
        if (!OPERATOR_ID.equals(value) && parse(value).isEmpty()) {
            throw new InvalidEntryException("'" + value + "' is not a participant: expected " + OPERATOR_ID
                    + " or <project>/<name>, where each is " + NAME_RULE);
        }
    }

    /** The agent called {@code name} in {@code project}. */
    public static ParticipantId of(String project, String name) {
        checkName(project, "project name");
        checkName(name, "name");
        if (RESERVED.contains(name)) {
            throw new InvalidEntryException("'" + name + "' is reserved and cannot be a name");
        }
        return new ParticipantId(project + "/" + name);
    }

    /**
     * The participant an address names, read from {@code project}: {@code operator}, a name
     * such as {@code astra} or {@code @astra} for that agent in {@code project}, or
     * {@code listenly/astra} or {@code listenly/@astra} for one in another project.
     */
    public static ParticipantId address(String address, String project) {
        Objects.requireNonNull(address, "address");
        if (OPERATOR_ID.equals(address)) {
            return OPERATOR;
        }
        Matcher matcher = ADDRESS.matcher(address);
        if (!matcher.matches()) {
            throw new InvalidEntryException("'" + address + "' is not an address: expected " + OPERATOR_ID
                    + ", <name>, or <project>/<name>, where each is " + NAME_RULE);
        }
        return of(matcher.group(1) != null ? matcher.group(1) : project, matcher.group(2));
    }

    /** Whether {@code name} is a valid name or project name, reserved words aside. */
    public static boolean isName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public boolean isHuman() {
        return OPERATOR_ID.equals(value);
    }

    /** The project an agent belongs to; empty for the human. */
    public Optional<String> project() {
        return parse(value).map(Agent::project);
    }

    /** The agent's name within its project; empty for the human. */
    public Optional<String> name() {
        return parse(value).map(Agent::name);
    }

    /** True for an agent of {@code project}. */
    public boolean belongsTo(String project) {
        return project().filter(project::equals).isPresent();
    }

    /**
     * How a reader in {@code project} sees this participant: {@code astra} for an agent of
     * that project, {@code sideband/@astra} for one elsewhere, {@code Operator} for the human.
     */
    public String label(String project) {
        return parse(value)
                .map(agent -> agent.project().equals(project) ? agent.name() : agent.project() + "/@" + agent.name())
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

    private static void checkName(String name, String what) {
        if (!isName(name)) {
            throw new InvalidEntryException("'" + name + "' is not a valid " + what + ": expected " + NAME_RULE);
        }
    }

    private static Optional<Agent> parse(String value) {
        Matcher matcher = AGENT.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String project = matcher.group(1);
        String name = matcher.group(2);
        if (!isName(project) || !isName(name) || RESERVED.contains(name)) {
            return Optional.empty();
        }
        return Optional.of(new Agent(project, name));
    }

    private record Agent(String project, String name) {
    }
}
