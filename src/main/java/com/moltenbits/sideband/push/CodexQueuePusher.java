package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Wakes a Codex session with {@code codex queue --thread <session id> --message <text>}.
 * The session id recorded at activation is Codex's thread id. The text is passed as one
 * argument vector element, never through a shell.
 */
@Singleton
class CodexQueuePusher implements HostPusher {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String executable;

    CodexQueuePusher(@Value("${sideband.codex.executable:codex}") String executable) {
        this.executable = executable;
    }

    @Override
    public Role role() {
        return Role.CODEX;
    }

    @Override
    public PushResult push(Session session, String text) {
        List<String> argv = List.of(executable, "queue", "--thread", session.id(), "--message", text);
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
