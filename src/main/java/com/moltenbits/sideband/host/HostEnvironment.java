package com.moltenbits.sideband.host;

import com.moltenbits.sideband.protocol.Role;

import java.util.Optional;

/**
 * What the calling process's environment says about the client it runs inside. Each client
 * marks the shells it spawns, so commands need not be told which client is calling.
 */
public interface HostEnvironment {

    /** The client this process runs inside, when one can be recognized. */
    Optional<Role> role();

    /** The client's identifier for the current session, when the client exposes it. */
    Optional<String> sessionId(Role role);

    /** The client's process id, when the client exposes it. */
    Optional<Long> parentPid(Role role);

    /** The role, or a clear error naming the flag that overrides detection. */
    default Role requireRole(String flag) {
        return role().orElseThrow(() -> new IllegalArgumentException(
                "cannot tell which client this is: run inside Claude Code or Codex, or pass " + flag));
    }
}
