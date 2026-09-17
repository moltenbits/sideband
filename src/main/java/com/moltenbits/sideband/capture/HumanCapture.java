package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

import java.util.Optional;

/** Journals a human's prompt verbatim, routes it, marks the originating turn, and pushes to recipients. */
public interface HumanCapture {

    Captured capture(Path stateDirectory, Role via, String body);

    /**
     * Captures the prompt the hook held for this session before the role joined, if any:
     * the hold is consumed and the entry journaled together, then pushed like any capture.
     * Empty when nothing was held for the session.
     */
    Optional<Captured> adopt(Path stateDirectory, Role via, String sessionId);
}
