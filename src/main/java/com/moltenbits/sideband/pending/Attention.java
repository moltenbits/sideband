package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

/**
 * Whether the operator's attention is wanted when a role's turn ends, derived from the
 * journal alone. A host notifies the operator at the end of every turn, but under Sideband
 * most turns are one client answering the other; this says which turn ends are the
 * operator's business. Reads only.
 */
public interface Attention {

    /** The verdict for the role whose turn just ended, with the reason in words for a log line. */
    Verdict atTurnEnd(Path stateDirectory, Role role);

    record Verdict(boolean wanted, String reason) {
    }
}
