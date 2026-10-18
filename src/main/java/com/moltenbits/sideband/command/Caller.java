package com.moltenbits.sideband.command;

import com.moltenbits.sideband.handoff.Handling;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.InstanceRule;
import com.moltenbits.sideband.session.Sessions;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Who the model's own command is, by the commands' rule of REQUIREMENTS.md 9.5a: the instance
 * whose record names the calling session or process in the project, or the client's default
 * name when the client has no records there and the other client does not hold that name.
 * Anything else is refused as invalid input, since acting as another joined instance would
 * write under its identity and move its bookmark; joining settles it.
 */
final class Caller {

    private Caller() {
    }

    /**
     * @param overrideFlag the hidden flag that names the client instead, for the error when the
     *                     environment names no client at all
     */
    static ParticipantId identify(HostEnvironment host, Sessions sessions, Path stateDirectory, String project, String overrideFlag) {
        Role role = host.requireRole(overrideFlag);
        InstanceRule.Identified identified = sessions.identify(stateDirectory, project, role,
                host.sessionId(role).orElse(null), host.process(role).orElse(null));
        if (identified.instance() != null) {
            return identified.instance();
        }
        String invocation = Handling.invocation(role);
        if (identified.taken() != null) {
            throw new IllegalArgumentException("this " + role.displayName() + " session holds no Sideband instance here, and "
                    + identified.taken().name().orElseThrow() + " is held by the other client in this project. "
                    + invocation + " joins as " + role.id() + ", taking it over, and " + invocation + " as <name> joins as <name>");
        }
        List<ParticipantId> joined = identified.records();
        throw new IllegalArgumentException("this " + role.displayName() + " session holds no Sideband instance here: "
                + joined.stream().map(ParticipantId::value).collect(Collectors.joining(", "))
                + (joined.size() == 1 ? " is" : " are") + " joined from other sessions. "
                + invocation + " joins as " + role.id() + ", and " + invocation + " as <name> joins as <name>");
    }

    /** A hidden override naming an agent by its address in {@code project}, never the operator. */
    static ParticipantId client(String override, String project, String flag) {
        ParticipantId participant = ParticipantId.address(override, project);
        if (participant.isHuman()) {
            throw new IllegalArgumentException(flag + " names an agent, never the operator");
        }
        return participant;
    }
}
