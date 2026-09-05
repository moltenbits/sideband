package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.NotARepositoryException;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.PushOutcome;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Session;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Entry points a client host calls directly, never a person. */
@Command(name = "hook", description = "Entry points for client hooks", mixinStandardHelpOptions = true,
        subcommands = HookCommand.Prompt.class)
public class HookCommand {

    /**
     * Both clients' {@code UserPromptSubmit} hook. Reads the hook payload on stdin and journals
     * the prompt verbatim when this session owns the detected client's cursor in the repository.
     * Delivered envelopes, slash commands, and shell commands are never captured. Capture
     * never blocks the prompt: any problem goes to stderr and the exit code is always 0.
     */
    @Command(name = "prompt", description = "Claude Code/Codex UserPromptSubmit hook: journal the human's prompt", mixinStandardHelpOptions = true)
    @Prototype
    public static class Prompt implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--agent", description = "Override automatic caller detection: claude or codex")
        Role agent;

        private final SidebandHome home;
        private final HostEnvironment host;
        private final RecipientState recipients;
        private final HumanCapture capture;
        private final ObjectMapper json;

        Prompt(SidebandHome home, HostEnvironment host, RecipientState recipients, HumanCapture capture, ObjectMapper json) {
            this.home = home;
            this.host = host;
            this.recipients = recipients;
            this.capture = capture;
            this.json = json;
        }

        @Override
        public Integer call() {
            try {
                return capturePrompt();
            } catch (IOException | RuntimeException e) {
                spec.commandLine().getErr().println("sideband hook: capture failed: " + e.getMessage());
                return ExitCode.OK;
            }
        }

        private int capturePrompt() throws IOException {
            Payload payload;
            try {
                payload = json.readValue(new String(System.in.readAllBytes(), UTF_8), Payload.class);
            } catch (IOException e) {
                spec.commandLine().getErr().println("sideband hook: unreadable payload: " + e.getMessage());
                return ExitCode.OK;
            }
            if (payload == null || (payload.hookEventName() != null
                    && !payload.hookEventName().equals("UserPromptSubmit"))) {
                return ExitCode.OK;
            }
            String prompt = payload.prompt() == null ? "" : payload.prompt();
            String trimmed = prompt.stripLeading();
            if (trimmed.isBlank() || trimmed.startsWith(Handoffs.ENVELOPE_MARKER) || trimmed.startsWith("/") || trimmed.startsWith("!")) {
                return ExitCode.OK;
            }
            Path cwd = payload.cwd() == null ? Path.of(System.getProperty("user.dir")) : Path.of(payload.cwd());
            Path stateDirectory;
            try {
                stateDirectory = home.locate(cwd);
            } catch (NotARepositoryException e) {
                return ExitCode.OK;
            }
            if (!Files.isDirectory(stateDirectory)) {
                return ExitCode.OK;
            }
            if (payload.sessionId() == null || payload.sessionId().isBlank()) {
                return ExitCode.OK;
            }
            Role role = agent != null ? agent : host.role().orElse(null);
            if (role == null) {
                // Both hosts use the same event name. A unique active session match identifies
                // the caller when hook shells omit the usual environment markers.
                var matching = Arrays.stream(Role.values())
                        .filter(candidate -> ownsSession(stateDirectory, candidate, payload.sessionId()))
                        .toList();
                if (matching.size() != 1) {
                    return ExitCode.OK;
                }
                role = matching.getFirst();
            }
            if (!ownsSession(stateDirectory, role, payload.sessionId())) {
                return ExitCode.OK;
            }
            Captured captured = capture.capture(stateDirectory, role, prompt);
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit", note(captured))));
            return ExitCode.OK;
        }

        private boolean ownsSession(Path stateDirectory, Role role, String sessionId) {
            Session session = recipients.load(stateDirectory, role).session();
            return session != null && session.isLive() && session.id().equals(sessionId);
        }

        private static String note(Captured captured) {
            String delivered = captured.pushes().stream()
                    .filter(p -> p.outcome() != PushOutcome.LISTENER_DELIVERS)
                    .map(p -> p.role().id() + "=" + p.outcome().id())
                    .collect(Collectors.joining(", "));
            return delivered.isEmpty()
                    ? "Sideband journaled this prompt. Do not capture it again."
                    : "Sideband journaled this prompt and delivered it: " + delivered + ". Do not capture or route it again.";
        }

        @Serdeable(naming = SnakeCaseStrategy.class)
        record Payload(@Nullable String prompt, @Nullable String cwd, @Nullable String sessionId,
                       @Nullable String hookEventName) {
        }

        @Serdeable
        record Response(HookOutput hookSpecificOutput) {
        }

        @Serdeable
        record HookOutput(String hookEventName, String additionalContext) {
        }
    }
}
