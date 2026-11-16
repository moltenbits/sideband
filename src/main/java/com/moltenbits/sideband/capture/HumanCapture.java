package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

import java.util.Optional;

/**
 * Journals a human's prompt verbatim, routes it, marks the originating turn, and pushes to
 * recipients. A prompt addressed to a project nobody registered is still journaled, to the
 * instance it was typed into, and the result says the route failed.
 */
public interface HumanCapture {

    Captured capture(Path stateDirectory, ParticipantId via, String body);

    /**
     * Captures the prompt the hook held for this session of {@code client} before it joined,
     * if any: the hold is consumed and the entry journaled together, then pushed like any
     * capture. Empty when nothing was held for the session.
     */
    Optional<Captured> adopt(Path stateDirectory, ParticipantId via, Role client, String sessionId);
}
