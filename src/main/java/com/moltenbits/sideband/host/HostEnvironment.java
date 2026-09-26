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

    /**
     * The client's host process, when the client names it: Claude Code sets {@code CLAUDE_PID}
     * in every shell and hook it runs. Codex names none, so this is empty for Codex, and so it
     * is when the named process is not running (REQUIREMENTS.md 9.5a).
     */
    Optional<HostProcess> process(Role role);

    /** The role, or a clear error naming the flag that overrides detection. */
    default Role requireRole(String flag) {
        return role().orElseThrow(() -> new IllegalArgumentException(
                "cannot tell which client this is: run inside Claude Code or Codex, or pass " + flag));
    }
}
