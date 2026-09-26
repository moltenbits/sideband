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
 * whose record names the calling session or process, or the unnamed instance of a role that
 * has no records at all. Anything else is refused as invalid input, since acting as another
 * joined instance would write under its identity and move its bookmark; joining settles it.
 */
final class Caller {

    private Caller() {
    }

    /**
     * @param overrideFlag the hidden flag that names the client instead, for the error when the
     *                     environment names no client at all
     */
    static ParticipantId identify(HostEnvironment host, Sessions sessions, Path stateDirectory, String overrideFlag) {
        Role role = host.requireRole(overrideFlag);
        InstanceRule.Identified identified = sessions.identify(stateDirectory, role,
                host.sessionId(role).orElse(null), host.process(role).orElse(null));
        if (identified.instance() != null) {
            return identified.instance();
        }
        List<ParticipantId> joined = identified.records();
        String invocation = Handling.invocation(role);
        throw new IllegalArgumentException("this " + role.displayName() + " session holds no Sideband instance here: "
                + joined.stream().map(ParticipantId::value).collect(Collectors.joining(", "))
                + (joined.size() == 1 ? " is" : " are") + " joined from other sessions. "
                + invocation + " joins as " + role.id() + ", and " + invocation + " as <name> joins as " + role.id() + ":<name>");
    }

    /** A hidden override that must name a client instance, never the operator. */
    static ParticipantId client(ParticipantId override, String flag) {
        if (override.isHuman()) {
            throw new IllegalArgumentException(flag + " names a client instance, never the operator");
        }
        return override;
    }
}
