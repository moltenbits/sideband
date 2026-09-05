package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.NotARepositoryException;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.PushOutcome;
import com.moltenbits.sideband.recipient.Pending;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Session;
import com.moltenbits.sideband.recipient.SessionRefresh;
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
import java.nio.file.InvalidPathException;
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
                try {
                    return failed("capture failed: " + e.getMessage());
                } catch (IOException unreportable) {
                    spec.commandLine().getErr().println("sideband hook: could not report the failure: " + unreportable.getMessage());
                    return ExitCode.OK;
                }
            }
        }

        private int capturePrompt() throws IOException {
            Payload payload;
            try {
                payload = json.readValue(new String(System.in.readAllBytes(), UTF_8), Payload.class);
            } catch (IOException e) {
                return failed("unreadable payload: " + e.getMessage());
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
            Path stateDirectory;
            try {
                Path cwd = payload.cwd() == null ? Path.of(System.getProperty("user.dir")) : Path.of(payload.cwd());
                stateDirectory = home.locate(cwd);
            } catch (InvalidPathException e) {
                return skipped("invalid working directory in the hook payload");
            } catch (NotARepositoryException e) {
                return ExitCode.OK;
            }
            if (!Files.isDirectory(stateDirectory)) {
                return ExitCode.OK;
            }
            if (payload.sessionId() == null || payload.sessionId().isBlank()) {
                return skipped("hook payload has no session_id");
            }
            Role role = agent != null ? agent : host.role().orElse(null);
            if (role == null) {
                // Both hosts use the same event name. A unique recorded session match identifies
                // the caller when hook shells omit the usual environment markers.
                var matching = Arrays.stream(Role.values())
                        .filter(candidate -> matchesSession(stateDirectory, candidate, payload.sessionId()))
                        .toList();
                if (matching.size() != 1) {
                    return skipped(matching.isEmpty() ? "no recorded session matches this caller; activate Sideband"
                            : "session matches multiple roles; use --agent to identify the caller");
                }
                role = matching.getFirst();
            }
            SessionRefresh refreshed = recipients.refreshSession(stateDirectory, role, payload.sessionId(),
                    host.parentPid(role).orElse(null));
            switch (refreshed) {
                case NOT_ACTIVE -> { return inactive(stateDirectory, role); }
                case SESSION_MISMATCH -> { return failed("another " + role.displayName() + " session owns Sideband in this repository"); }
                case CALLER_UNAVAILABLE -> { return failed("the recorded " + role.displayName() + " host is dead and a living caller process could not be identified; reactivate Sideband"); }
                case READY, REFRESHED -> { /* capture below */ }
            }
            Captured captured = capture.capture(stateDirectory, role, prompt);
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit", note(captured))));
            return ExitCode.OK;
        }

        /** A prompt that was never meant to be captured, or a repository where Sideband is not in use: stderr only. */
        private int skipped(String reason) {
            spec.commandLine().getErr().println("sideband hook: capture skipped: " + reason);
            return ExitCode.OK;
        }

        /**
         * A prompt that should have been journaled and was not. The host shows the model only
         * the context field, so a failure must go there too or the loss is invisible.
         */
        private int failed(String reason) throws IOException {
            spec.commandLine().getErr().println("sideband hook: capture failed: " + reason);
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit",
                    "Sideband could not journal this prompt: " + reason + ". Tell the user; the prompt is not in the journal.")));
            return ExitCode.OK;
        }

        /**
         * Sideband is not active for the caller, which is normal in a repository that has it
         * installed but is not using it right now. The model hears about it only when entries
         * addressed to the caller are waiting, so nothing sits unread in silence.
         */
        private int inactive(Path stateDirectory, Role role) throws IOException {
            Pending pending = recipients.pending(stateDirectory, role);
            int waiting = pending.backlog().size() + pending.live().size();
            if (waiting == 0) {
                return skipped("Sideband is not activated for " + role.id());
            }
            spec.commandLine().getErr().println("sideband hook: capture skipped: Sideband is not activated for " + role.id()
                    + "; " + waiting + " waiting");
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit",
                    "Sideband is not active in this session and " + waiting + (waiting == 1 ? " entry" : " entries")
                    + " addressed to " + role.displayName() + " " + (waiting == 1 ? "is" : "are")
                    + " waiting. Tell the user; /sideband activates and reviews them.")));
            return ExitCode.OK;
        }

        private boolean matchesSession(Path stateDirectory, Role role, String sessionId) {
            Session session = recipients.load(stateDirectory, role).session();
            return session != null && session.id().equals(sessionId);
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
