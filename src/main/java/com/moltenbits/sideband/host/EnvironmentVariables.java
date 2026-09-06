package com.moltenbits.sideband.host;

import com.moltenbits.sideband.protocol.Role;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Optional;

/**
 * Codex sets {@code CODEX_THREAD_ID} in its shells; Claude Code sets {@code CLAUDECODE} and
 * {@code CLAUDE_CODE_SESSION_ID}. Nothing about the process tree is consulted: a shell
 * without a marker belongs to no client, and the hook registrations name their client.
 */
@Singleton
class EnvironmentVariables implements HostEnvironment {

    static final String CODEX_THREAD = "CODEX_THREAD_ID";
    static final String CLAUDE_MARKER = "CLAUDECODE";
    static final String CLAUDE_SESSION = "CLAUDE_CODE_SESSION_ID";

    private final Map<String, String> env;

    EnvironmentVariables() {
        this(System.getenv());
    }

    EnvironmentVariables(Map<String, String> env) {
        this.env = env;
    }

    @Override
    public Optional<Role> role() {
        if (present(CODEX_THREAD)) {
            return Optional.of(Role.CODEX);
        }
        if (present(CLAUDE_MARKER) || present(CLAUDE_SESSION)) {
            return Optional.of(Role.CLAUDE);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> sessionId(Role role) {
        return value(role == Role.CODEX ? CODEX_THREAD : CLAUDE_SESSION);
    }

    private boolean present(String name) {
        return value(name).isPresent();
    }

    private Optional<String> value(String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }
}
