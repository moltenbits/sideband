package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

/** Derives what an instance still has to look at from the journal and its session record. Reads only. */
public interface Pending {

    /** @param client the client the instance runs in, whose handling instructions the report carries */
    PendingReport report(Path stateDirectory, ParticipantId instance, Role client);
}
