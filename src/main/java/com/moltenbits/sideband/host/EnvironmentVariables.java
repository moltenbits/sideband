package com.moltenbits.sideband.host;

import com.moltenbits.sideband.protocol.Role;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Optional;

/**
 * Codex sets {@code CODEX_THREAD_ID} in its shells; Claude Code sets {@code CLAUDECODE},
 * {@code CLAUDE_CODE_SESSION_ID}, and {@code CLAUDE_PID}. The process tree is never walked:
 * a shell without a marker belongs to no client, and the hook registrations name their
 * client. The one process consulted is the one Claude Code names, to learn when it started.
 */
@Singleton
class EnvironmentVariables implements HostEnvironment {

    static final String CODEX_THREAD = "CODEX_THREAD_ID";
    static final String CLAUDE_MARKER = "CLAUDECODE";
    static final String CLAUDE_SESSION = "CLAUDE_CODE_SESSION_ID";
    static final String CLAUDE_PID = "CLAUDE_PID";

    private final Map<String, String> env;
    private final HostProcesses processes;

    @Inject
    EnvironmentVariables(HostProcesses processes) {
        this(System.getenv(), processes);
    }

    EnvironmentVariables(Map<String, String> env, HostProcesses processes) {
        this.env = env;
        this.processes = processes;
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

    @Override
    public Optional<HostProcess> process(Role role) {
        if (role != Role.CLAUDE) {
            return Optional.empty();
        }
        try {
            return value(CLAUDE_PID).map(Long::parseLong).flatMap(processes::describe);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private boolean present(String name) {
        return value(name).isPresent();
    }

    private Optional<String> value(String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }
}
