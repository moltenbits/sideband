package com.moltenbits.sideband.host;

import com.moltenbits.sideband.protocol.Role;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Optional;

/**
 * Codex sets {@code CODEX_THREAD_ID} in its shells; Claude Code sets {@code CLAUDECODE},
 * {@code CLAUDE_CODE_SESSION_ID}, and {@code CLAUDE_PID}. The client process itself is found
 * from the environment when published, otherwise by walking up from this process.
 */
@Singleton
class EnvironmentVariables implements HostEnvironment {

    static final String CODEX_THREAD = "CODEX_THREAD_ID";
    static final String CLAUDE_MARKER = "CLAUDECODE";
    static final String CLAUDE_SESSION = "CLAUDE_CODE_SESSION_ID";
    static final String CLAUDE_PID = "CLAUDE_PID";

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

    /**
     * Claude Code publishes its pid. Otherwise walk this process's ancestors for the client
     * executable: the command runs in a shell the client spawned, so the client is a few
     * levels up.
     */
    @Override
    public Optional<Long> parentPid(Role role) {
        if (role == Role.CLAUDE) {
            try {
                Optional<Long> published = value(CLAUDE_PID).map(Long::parseLong);
                if (published.isPresent()) {
                    return published;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the ancestry walk
            }
        }
        return ancestorNamed(role.id());
    }

    private static Optional<Long> ancestorNamed(String name) {
        Optional<ProcessHandle> current = ProcessHandle.current().parent();
        for (int depth = 0; current.isPresent() && depth < 12; depth++) {
            ProcessHandle handle = current.get();
            String command = handle.info().command().orElse("");
            String base = command.substring(command.lastIndexOf('/') + 1);
            if (base.equals(name) || base.startsWith(name + "-") || base.startsWith(name + ".")) {
                return Optional.of(handle.pid());
            }
            current = handle.parent();
        }
        return Optional.empty();
    }

    private boolean present(String name) {
        return value(name).isPresent();
    }

    private Optional<String> value(String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }
}
