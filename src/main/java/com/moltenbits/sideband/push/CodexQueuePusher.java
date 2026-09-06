package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Wakes a Codex session with {@code codex queue --thread <thread id> --message <text>}.
 * The thread id is the session id Codex recorded when it joined. The text is passed as one
 * argument vector element, never through a shell.
 */
@Singleton
class CodexQueuePusher implements HostPusher {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String executable;
    private final Sessions sessions;

    CodexQueuePusher(@Value("${sideband.codex.executable:codex}") String executable, Sessions sessions) {
        this.executable = executable;
        this.sessions = sessions;
    }

    @Override
    public Role role() {
        return Role.CODEX;
    }

    @Override
    public PushResult push(Path stateDirectory, String text) {
        Optional<Session> session = sessions.load(stateDirectory, Role.CODEX);
        if (session.isEmpty()) {
            return new PushResult(Role.CODEX, PushOutcome.NO_SESSION, null);
        }
        List<String> argv = List.of(executable, "queue", "--thread", session.get().id(), "--message", text);
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), UTF_8).strip();
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new PushResult(Role.CODEX, PushOutcome.FAILED, "codex queue did not finish within " + TIMEOUT.toSeconds() + "s");
            }
            if (process.exitValue() != 0) {
                return new PushResult(Role.CODEX, PushOutcome.FAILED, "codex queue exited " + process.exitValue() + ": " + output);
            }
            return new PushResult(Role.CODEX, PushOutcome.PUSHED, output);
        } catch (IOException e) {
            return new PushResult(Role.CODEX, PushOutcome.FAILED, "could not run " + executable + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PushResult(Role.CODEX, PushOutcome.FAILED, "interrupted while running codex queue");
        }
    }
}
