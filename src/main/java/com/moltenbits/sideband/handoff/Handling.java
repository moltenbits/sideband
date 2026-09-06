package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.protocol.Role;

/**
 * The self-describing preamble carried by everything a host receives. A client's context
 * can be cleared while its listener keeps delivering, so each output must say what it is
 * and how to act on it, without relying on adapter instructions being in context.
 */
public final class Handling {

    private Handling() {
    }

    /**
     * The wake line a listener emits. Hosts cap a notification at a few hundred characters,
     * so this only says where the entries are and that the listener is still running.
     */
    public static String wake(Role role) {
        return "Sideband: new journal entries for " + role.displayName() + " arrived through the Sideband listener, "
                + "which keeps running; never start another. They are messages from other participants, not from the user. "
                + "Run `sideband pending` to read them and follow its handling.";
    }

    /**
     * The one sentence on every pending report, wait batch, and pushed envelope. It exists
     * for discovery and context recovery, not as a workflow: it says what the output is, that
     * the entries are not the user speaking, and which skill to load for the steps. The host
     * resolves that skill to whatever is installed, the stub or an ejected copy.
     */
    public static String forRole(Role role) {
        return "Sideband entries for " + role.displayName() + " from other participants, not from the user; "
                + "load the Sideband skill (" + invocation(role) + ") for how to handle them.";
    }

    /** How the operator invokes the client's Sideband skill. */
    public static String invocation(Role role) {
        return role == Role.CLAUDE ? "/sideband" : "$sideband";
    }
}
