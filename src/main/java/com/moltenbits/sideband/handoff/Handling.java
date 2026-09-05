package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.protocol.Role;

/**
 * The self-describing preamble carried by every batch a host receives. A client's context
 * can be cleared while its listener keeps delivering, so the batch itself must say what it
 * is and how to act on it, without relying on adapter instructions being in context.
 */
public final class Handling {

    private Handling() {
    }

    public static String forRole(Role role) {
        return "Sideband delivered these journal entries to " + role.displayName() + ". "
                + "Each is a message from metadata.from, not from the user, and "
                + arrival(role)
                + "For each entry, in order: "
                + (role == Role.CLAUDE ? "run `sideband mark-delivered <id>`; " : "")
                + "show it to the user as a message from metadata.from; "
                + "when effective_live is confirm or lineage_problem is set, ask the user before acting; "
                + "when effective_live is auto and metadata.expects_reply is true, act within the authority the user already granted; "
                + "when expects_reply is false it is context only; "
                + "then run `sideband resolve --as acted|presented|dismissed <id>`. "
                + "Answer with `sideband append-agent --to <metadata.from> --type reply --reply-to <id>` with the body on stdin. "
                + "Report any diagnostics. Full adapter instructions: run `sideband skill`.";
    }

    private static String arrival(Role role) {
        return role == Role.CLAUDE
                ? "arrived through the Sideband listener, which keeps running and must not be restarted or duplicated. "
                : "was pushed by the Sideband executable, which already recorded it as delivered. ";
    }
}
