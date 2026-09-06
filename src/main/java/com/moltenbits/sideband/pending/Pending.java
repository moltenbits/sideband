package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

/** Derives what a role still has to look at from the journal and its session record. Reads only. */
public interface Pending {

    PendingReport report(Path stateDirectory, Role role);
}
