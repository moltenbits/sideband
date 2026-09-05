package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

/** Journals a human's prompt verbatim, routes it, marks the originating turn, and pushes to recipients. */
public interface HumanCapture {

    Captured capture(Path stateDirectory, Role via, String body);
}
