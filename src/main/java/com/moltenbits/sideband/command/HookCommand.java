package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.CaptureFailedException;
import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.PushOutcome;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.SessionRefresh;
import com.moltenbits.sideband.session.Sessions;
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
import java.util.Optional;
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
    @Command(name = "prompt", description = "Claude Code/Codex UserPromptSubmit hook: record the human's prompt in the Sideband discussion", mixinStandardHelpOptions = true)
    @Prototype
    public static class Prompt implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--agent", description = "Override automatic caller detection: claude or codex")
        Role agent;

        private final SidebandHome home;
        private final HostEnvironment host;
        private final Sessions sessions;
        private final Pending pending;
        private final HumanCapture capture;
        private final ObjectMapper json;

        Prompt(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, HumanCapture capture, ObjectMapper json) {
            this.home = home;
            this.host = host;
            this.sessions = sessions;
            this.pending = pending;
            this.capture = capture;
            this.json = json;
        }

        @Override
        public Integer call() {
            try {
                return capturePrompt();
            } catch (CaptureFailedException e) {
                try {
                    return switch (e.stage()) {
                        case NOT_JOURNALED -> failed("capture failed: " + e.getMessage());
                        case UNCERTAIN -> uncertain(e.getMessage());
                        case JOURNALED -> journaledButIncomplete(e.journaledId(), e.getMessage());
                    };
                } catch (IOException unreportable) {
                    spec.commandLine().getErr().println("sideband hook: could not report the failure: " + unreportable.getMessage());
                    return ExitCode.OK;
                }
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
            }
            if (!Files.isDirectory(stateDirectory)) {
                return ExitCode.OK;
            }
            Role role = agent != null ? agent : host.role().orElse(null);
            if (payload.sessionId() == null || payload.sessionId().isBlank()) {
                // Without a session id the caller cannot be tied to a recorded session. That is a
                // host defect worth hearing about only where Sideband is actually in use.
                String reason = "hook payload has no session_id";
                return (role != null ? isActive(stateDirectory, role) : anyActive(stateDirectory)) ? failed(reason) : skipped(reason);
            }
            if (role == null) {
                // Both hosts use the same event name. When hook shells omit the usual environment
                // markers, the caller is the one role whose recorded session it presents, or whose
                // recorded host process it runs inside (a cleared conversation carries a new id).
                var matching = Arrays.stream(Role.values())
                        .filter(candidate -> matchesSession(stateDirectory, candidate, payload.sessionId()))
                        .toList();
                if (matching.size() != 1) {
                    String reason = matching.isEmpty() ? "no recorded session matches this caller; join Sideband"
                            : "session matches multiple roles; use --agent to identify the caller";
                    return anyActive(stateDirectory) ? failed(reason) : skipped(reason);
                }
                role = matching.getFirst();
            }
            SessionRefresh refreshed = sessions.refresh(stateDirectory, role, payload.sessionId(),
                    host.parentPid(role).orElse(null));
            switch (refreshed) {
                case NOT_ACTIVE -> { return inactive(stateDirectory, role); }
                case SESSION_MISMATCH -> { return failed("another " + role.displayName() + " session owns Sideband in this repository"); }
                case CALLER_UNAVAILABLE -> { return failed("the recorded " + role.displayName() + " host is dead and a living caller process could not be identified; join Sideband again"); }
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
            return report("Sideband could not record this prompt: " + reason
                    + ". It is not in the discussion; tell the user, then capture it with `sideband capture-human` if Sideband is active.");
        }

        /** The append itself failed, so the journal may or may not hold the entry. */
        private int uncertain(String reason) throws IOException {
            spec.commandLine().getErr().println("sideband hook: capture failed during the append: " + reason);
            return report("Sideband may not have recorded this prompt: " + reason
                    + ". Tell the user. Do not capture it again unless the Sideband discussion shows it is missing.");
        }

        /** The entry is journaled; only what follows the append failed. */
        private int journaledButIncomplete(String id, String reason) throws IOException {
            spec.commandLine().getErr().println("sideband hook: recorded " + id + " but could not finish: " + reason);
            return report("Sideband recorded this prompt as " + id + " but could not finish afterwards: " + reason
                    + ". Do not capture it again. Tell the user; its delivery or cursor update may be missing.");
        }

        private int report(String context) throws IOException {
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit", context)));
            return ExitCode.OK;
        }

        /**
         * Sideband is not active for the caller, which is normal in a repository that has it
         * installed but is not using it right now. The model hears about it only when entries
         * addressed to the caller are waiting, so nothing sits unread in silence.
         */
        private int inactive(Path stateDirectory, Role role) throws IOException {
            int waiting = pending.report(stateDirectory, role).waiting();
            if (waiting == 0) {
                return skipped("Sideband is not joined as " + role.id());
            }
            spec.commandLine().getErr().println("sideband hook: capture skipped: Sideband is not joined as " + role.id()
                    + "; " + waiting + " waiting");
            String invocation = role == Role.CLAUDE ? "/sideband" : "$sideband";
            return report("Sideband is not active in this session and " + waiting + (waiting == 1 ? " entry" : " entries")
                    + " addressed to " + role.displayName() + " " + (waiting == 1 ? "is" : "are")
                    + " waiting. Tell the user; " + invocation + " joins and reviews them.");
        }

        private boolean matchesSession(Path stateDirectory, Role role, String sessionId) {
            Optional<Session> session = sessions.load(stateDirectory, role);
            if (session.isEmpty()) {
                return false;
            }
            if (session.get().id().equals(sessionId)) {
                return true;
            }
            Optional<Long> caller = host.parentPid(role);
            return caller.isPresent() && caller.get().equals(session.get().parentPid());
        }

        private boolean isActive(Path stateDirectory, Role role) {
            return sessions.load(stateDirectory, role).isPresent();
        }

        private boolean anyActive(Path stateDirectory) {
            return Arrays.stream(Role.values()).anyMatch(role -> isActive(stateDirectory, role));
        }

        private static String note(Captured captured) {
            String delivered = captured.pushes().stream()
                    .filter(p -> p.outcome() != PushOutcome.LISTENER_DELIVERS)
                    .map(p -> p.role().id() + "=" + p.outcome().id())
                    .collect(Collectors.joining(", "));
            String id = captured.metadata().id();
            return delivered.isEmpty()
                    ? "Sideband recorded this prompt as " + id + ". Do not capture it again; cite it as --caused-by when delegating."
                    : "Sideband recorded this prompt as " + id + " and delivered it: " + delivered
                    + ". Do not capture or route it again; cite it as --caused-by when delegating.";
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
