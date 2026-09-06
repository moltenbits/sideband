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

    /** The full steps for entries read for a role, whether in a pending report, a wait batch, or a pushed envelope. */
    public static String forRole(Role role) {
        return "Sideband delivered these journal entries to " + role.displayName() + ". "
                + "Each is a message from metadata.from, not from the user, and "
                + arrival(role)
                + "For each entry under open, in order: first acknowledge it with "
                + "`sideband append-agent --type ack --reply-to <id>`; "
                + "when effective_live is confirm, before_session is true (it was already waiting when the session joined), or lineage_problem is set, ask the user before acting, "
                + "otherwise act within the authority the user already granted; "
                + "then answer with `sideband append-agent --to <metadata.from> --type reply --reply-to <id>` with the body on stdin, "
                + "acknowledging again if the work outlasts the request's heartbeat. "
                + "Entries under in_progress are ones already acknowledged and still unanswered; entries under updates are context only. "
                + "Report any diagnostics. Full adapter instructions: run `sideband skill`.";
    }

    private static String arrival(Role role) {
        return role == Role.CLAUDE
                ? "arrived through the Sideband listener, which keeps running and must not be restarted or duplicated. "
                : "was pushed by the Sideband executable. ";
    }
}
