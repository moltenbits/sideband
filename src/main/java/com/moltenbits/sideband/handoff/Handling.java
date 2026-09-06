package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.protocol.Role;

/**
 * The {@code intent} sentence on everything a host receives. A client's context can be
 * cleared while its listener keeps delivering, so each output says what it is and which
 * skill holds the instructions; the host resolves that skill to whatever is installed, the
 * stub or an ejected copy. It is discovery, never a workflow.
 */
public final class Handling {

    private Handling() {
    }

    /** The one sentence for the client's own skill. */
    public static String forRole(Role role) {
        return "Sideband delivery; use the Sideband skill (" + invocation(role) + ") for handling instructions";
    }

    /** The listener's wake line says the same; the counts beside it say what arrived. */
    public static String wake(Role role) {
        return forRole(role);
    }

    /** How the operator invokes the client's Sideband skill. */
    public static String invocation(Role role) {
        return role == Role.CLAUDE ? "/sideband" : "$sideband";
    }
}
