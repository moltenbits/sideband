package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.ParticipantId;

import java.nio.file.Path;

/** Derives what an instance still has to look at from the journal and its session record. Reads only. */
public interface Pending {

    PendingReport report(Path stateDirectory, ParticipantId instance);
}
