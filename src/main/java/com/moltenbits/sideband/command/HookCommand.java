package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.CaptureFailedException;
import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.pending.Attention;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.PushOutcome;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.session.Session;
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
import java.io.PrintWriter;
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
        subcommands = {HookCommand.Prompt.class, HookCommand.SessionStart.class, HookCommand.Notify.class})
public class HookCommand {

    /**
     * The repository's state directory for the working directory a hook payload names, or
     * empty when that path is not even a path. A repository without Sideband is a directory
     * that does not exist, which callers treat as nothing to do.
     */
    static Optional<Path> stateDirectory(SidebandHome home, @Nullable String cwd) {
        try {
            return Optional.of(home.locate(cwd == null ? Path.of(System.getProperty("user.dir")) : Path.of(cwd)));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Moves the joined role to the conversation the operator is looking at. A client that
     * starts a new conversation in place, as a clear does, may keep the old one alive, and
     * a push addressed to it would run there unseen; so whenever the operator's own input
     * arrives from a conversation other than the recorded one, the record follows. Only the
     * operator's input counts: a delivered envelope or a host notice says nothing about
     * where the operator is, and the callers never pass those here.
     */
    static void follow(Sessions sessions, PrintWriter err, Path stateDirectory, Role role, Session current, @Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.equals(current.id())) {
            return;
        }
        sessions.relocate(stateDirectory, role, sessionId);
        err.println("sideband hook: " + role.id() + " now delivers to " + sessionId);
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Payload(@Nullable String prompt, @Nullable String cwd, @Nullable String hookEventName,
                   @Nullable String sessionId, @Nullable String source, @Nullable String notificationType) {
    }

    @Serdeable
    record Response(HookOutput hookSpecificOutput) {
    }

    @Serdeable
    record HookOutput(String hookEventName, String additionalContext) {
    }

    /**
     * Both clients' {@code SessionStart} hook, registered for the {@code clear} source only.
     * A clear replaces the conversation on screen with a new one before any prompt is
     * typed, so the prompt hook cannot move the role until the operator speaks; this hook
     * moves it at once and tells the new conversation that Sideband is live in it. Every
     * other source keeps the conversation the role is in, or is a new client whose first
     * prompt will claim the role through the prompt hook. Never blocks the host: any problem
     * goes to stderr and the exit code is always 0.
     */
    @Command(name = "session-start", description = "Claude Code/Codex SessionStart hook: after a clear, move the joined role to the new conversation", mixinStandardHelpOptions = true)
    @Prototype
    public static class SessionStart implements Callable<Integer> {

        static final String EVENT = "SessionStart";
        static final String SOURCE = "clear";

        @Spec
        CommandSpec spec;

        @Option(names = "--agent", description = "The client whose hook this is, claude or codex; init registers it, and it wins over the shell markers")
        Role agent;

        private final SidebandHome home;
        private final HostEnvironment host;
        private final Sessions sessions;
        private final Pending pending;
        private final ObjectMapper json;

        SessionStart(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, ObjectMapper json) {
            this.home = home;
            this.host = host;
            this.sessions = sessions;
            this.pending = pending;
            this.json = json;
        }

        @Override
        public Integer call() {
            try {
                return followClear();
            } catch (IOException | RuntimeException e) {
                spec.commandLine().getErr().println("sideband hook: session start ignored: " + e.getMessage());
                return ExitCode.OK;
            }
        }

        private int followClear() throws IOException {
            Payload payload;
            try {
                payload = json.readValue(new String(System.in.readAllBytes(), UTF_8), Payload.class);
            } catch (IOException e) {
                return skipped("unreadable payload: " + e.getMessage());
            }
            if (payload == null || (payload.hookEventName() != null && !payload.hookEventName().equals(EVENT))) {
                return ExitCode.OK;
            }
            if (!SOURCE.equals(payload.source())) {
                return skipped("source " + payload.source() + " keeps the conversation the role is in");
            }
            if (payload.sessionId() == null || payload.sessionId().isBlank()) {
                return skipped("no session id in the hook payload");
            }
            Optional<Path> stateDirectory = stateDirectory(home, payload.cwd());
            if (stateDirectory.isEmpty()) {
                return skipped("invalid working directory in the hook payload");
            }
            if (!Files.isDirectory(stateDirectory.get())) {
                return ExitCode.OK;
            }
            Role role = agent != null ? agent : host.role().orElse(null);
            if (role == null) {
                return skipped("cannot tell which client this is; register the hook with --agent claude or --agent codex");
            }
            Optional<Session> current = sessions.load(stateDirectory.get(), role);
            if (current.isEmpty()) {
                return skipped("Sideband is not joined as " + role.id());
            }
            follow(sessions, spec.commandLine().getErr(), stateDirectory.get(), role, current.get(), payload.sessionId());
            // The new conversation remembers nothing, so an acknowledged request counts as
            // much as an open one: only the memory of taking it up was lost.
            int waiting = pending.report(stateDirectory.get(), role).unfinished();
            String invocation = role == Role.CLAUDE ? "/sideband" : "$sideband";
            String context = "Sideband is joined as " + role.displayName() + " in this repository and delivers to this conversation"
                    + (waiting == 0 ? "" : "; " + waiting + (waiting == 1 ? " entry" : " entries") + " addressed to "
                    + role.displayName() + " " + (waiting == 1 ? "is" : "are") + " waiting")
                    + ". " + invocation + " has the handling instructions.";
            Output.print(spec, json, new Response(new HookOutput(EVENT, context)));
            return ExitCode.OK;
        }

        private int skipped(String reason) {
            spec.commandLine().getErr().println("sideband hook: session start ignored: " + reason);
            return ExitCode.OK;
        }
    }

    /**
     * Both clients' {@code UserPromptSubmit} hook. Reads the hook payload on stdin and journals
     * the prompt verbatim, attributed to the operator via the calling client, whenever that
     * client's role has joined in the repository. Delivered envelopes, slash commands, shell
     * commands, and the host's own notifications are never captured. Capture never blocks the prompt: any problem goes to
     * stderr and the exit code is always 0.
     */
    @Command(name = "prompt", description = "Claude Code/Codex UserPromptSubmit hook: record the human's prompt in the Sideband discussion", mixinStandardHelpOptions = true)
    @Prototype
    public static class Prompt implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--agent", description = "The client whose hook this is, claude or codex; init registers it, and it wins over the shell markers")
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
                    return e.stage() == CaptureFailedException.Stage.JOURNALED
                            ? recordedButNotDelivered(e.journaledId(), e.getMessage())
                            : failed(e.getMessage());
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
            if (trimmed.startsWith(Handoffs.ENVELOPE_MARKER) || isPushedEnvelope(trimmed) || isHostNotification(trimmed)) {
                return ExitCode.OK; // the executable or the host speaking: never the operator, and not where the operator is
            }
            String message = skillMessage(trimmed);
            boolean capturable = message != null
                    || !(isSkillCommand(trimmed) || trimmed.isBlank() || trimmed.startsWith("/") || trimmed.startsWith("!"));
            if (message != null) {
                prompt = message; // the operator's words typed as the skill's argument: capture them, not the command
            }
            Optional<Path> located = stateDirectory(home, payload.cwd());
            if (located.isEmpty()) {
                return skipped("invalid working directory in the hook payload");
            }
            Path stateDirectory = located.get();
            if (!Files.isDirectory(stateDirectory)) {
                return ExitCode.OK;
            }
            // Both hosts use the same event name and payload shape, so the client is known only
            // from the registered --agent or the shell's markers. Which conversation or process
            // is calling does not matter: the prompt belongs to whoever holds the role here.
            Role role = agent != null ? agent : host.role().orElse(null);
            if (role == null) {
                if (!capturable) {
                    return ExitCode.OK;
                }
                String reason = "cannot tell which client this is; register the hook with --agent claude or --agent codex";
                return anyActive(stateDirectory) ? failed(reason) : skipped(reason);
            }
            Optional<Session> current = sessions.load(stateDirectory, role);
            if (current.isEmpty()) {
                return capturable ? inactive(stateDirectory, role) : ExitCode.OK;
            }
            // The operator typed this, so this is the conversation the operator is looking at:
            // even a prompt that is not recorded moves the role there.
            follow(sessions, spec.commandLine().getErr(), stateDirectory, role, current.get(), payload.sessionId());
            if (!capturable) {
                return ExitCode.OK;
            }
            Captured captured = capture.capture(stateDirectory, role, prompt);
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit", note(captured))));
            return ExitCode.OK;
        }

        /** A prompt that was never meant to be captured, or a repository where Sideband is not in use: stderr only. */
        /** The skill's own argument words; anything else after {@code /sideband} or {@code $sideband} is a message. */
        private static final java.util.Set<String> SKILL_WORDS = java.util.Set.of("help", "status", "pending", "off");

        /**
         * The operator's words when the prompt is the Sideband skill invoked with a message,
         * such as {@code /sideband @codex look at this}; null for any other prompt, including
         * the skill alone or with one of its own argument words.
         */
        static String skillMessage(String trimmed) {
            String rest = skillArgument(trimmed);
            return rest == null || rest.isEmpty() || SKILL_WORDS.contains(rest.toLowerCase(java.util.Locale.ROOT)) ? null : rest;
        }

        /** A pushed envelope inside the tag Claude Code gives its own cross-session messages: transport, never the operator typing. */
        static boolean isPushedEnvelope(String trimmed) {
            return trimmed.startsWith("<cross-session-message");
        }

        /**
         * Claude Code delivers its own notices to the model through the prompt hook too: a
         * background task finishing, a system reminder. They are the host speaking, never the
         * operator, so they are not recorded.
         */
        static boolean isHostNotification(String trimmed) {
            return trimmed.startsWith("<task-notification>") || trimmed.startsWith("<system-reminder>")
                    || trimmed.startsWith("[SYSTEM NOTIFICATION");
        }

        /** The skill invoked alone or with one of its own words: a command for the model, never the operator's words. */
        static boolean isSkillCommand(String trimmed) {
            String rest = skillArgument(trimmed);
            return rest != null && (rest.isEmpty() || SKILL_WORDS.contains(rest.toLowerCase(java.util.Locale.ROOT)));
        }

        /** What follows the skill invocation, stripped; null when the prompt is not the skill at all. */
        private static String skillArgument(String trimmed) {
            for (String invocation : new String[] {"/sideband", "$sideband"}) {
                if (trimmed.equals(invocation)) {
                    return "";
                }
                if (trimmed.startsWith(invocation) && trimmed.length() > invocation.length()
                        && Character.isWhitespace(trimmed.charAt(invocation.length()))) {
                    return trimmed.substring(invocation.length()).strip();
                }
            }
            return null;
        }

        private int skipped(String reason) {
            spec.commandLine().getErr().println("sideband hook: capture skipped: " + reason);
            return ExitCode.OK;
        }

        /**
         * Recording could not be completed or confirmed; the entry may or may not exist. The
         * host shows the model only the context field, so the failure goes there, and the
         * model tells the operator. Nothing else: no second attempt by anyone.
         */
        private int failed(String reason) throws IOException {
            spec.commandLine().getErr().println("sideband hook: capture failed: " + reason);
            return report("Sideband could not confirm recording this prompt: " + reason + ". Tell the user.");
        }

        /** The entry is recorded; only the push to the recipient failed. */
        private int recordedButNotDelivered(String id, String reason) throws IOException {
            spec.commandLine().getErr().println("sideband hook: recorded " + id + " but could not deliver it: " + reason);
            return report("Sideband recorded this prompt as " + id + " but could not deliver it: " + reason + ". Tell the user.");
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

        private boolean anyActive(Path stateDirectory) {
            return Arrays.stream(Role.values()).anyMatch(role -> sessions.load(stateDirectory, role).isPresent());
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
    }

    /**
     * Wraps a host's notifier, the command that turns a hook event into a desktop notification,
     * so that under Sideband the operator hears about a turn end only when it is theirs: the
     * host fires its Stop hook at the end of every turn, and a pushed envelope starts a turn
     * like a typed prompt does, so without this every exchange between the clients rings.
     * The verdict comes from {@link Attention}. Everything that is not a turn end passes
     * through, a permission prompt above all. On a prompt event, where the wrapped command is
     * usually the notifier's dismiss, the operator's own words pass through and a delivered
     * envelope does not, so an envelope never clears a notification the operator has not
     * seen. A session Sideband is not joined in, a repository without Sideband, or a payload
     * that cannot be read all pass through: the client alone behaves as it would without
     * Sideband. The wrapped command gets the payload on its stdin, byte for byte. Never blocks
     * the host: the exit code is always 0, whatever the wrapped command did.
     */
    @Command(name = "notify", description = "Claude Code/Codex Stop, Notification, PermissionRequest, or UserPromptSubmit hook: run the wrapped notifier only when the turn end is the operator's business", mixinStandardHelpOptions = true)
    @Prototype
    public static class Notify implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--agent", description = "The client whose hook this is, claude or codex; without it the payload's session id says which joined role is calling")
        Role agent;

        @Option(names = "--run", required = true, paramLabel = "<command>",
                description = "The notifier to wrap, run through the shell with the hook payload on its stdin, for example: grrr hook notify --appId MyProject")
        String run;

        private final SidebandHome home;
        private final Sessions sessions;
        private final Attention attention;
        private final ObjectMapper json;

        Notify(SidebandHome home, Sessions sessions, Attention attention, ObjectMapper json) {
            this.home = home;
            this.sessions = sessions;
            this.attention = attention;
            this.json = json;
        }

        @Override
        public Integer call() {
            byte[] raw;
            try {
                raw = System.in.readAllBytes();
            } catch (IOException e) {
                err().println("sideband hook: notification passed through: unreadable stdin: " + e.getMessage());
                return forward(new byte[0]);
            }
            try {
                String reason = held(raw);
                if (reason != null) {
                    err().println("sideband hook: notification held: " + reason);
                    return ExitCode.OK;
                }
            } catch (RuntimeException e) {
                err().println("sideband hook: notification passed through: " + e.getMessage());
            }
            return forward(raw);
        }

        /** The reason to hold the notification back, or null to let it through. */
        private @Nullable String held(byte[] raw) {
            Payload payload;
            try {
                payload = json.readValue(new String(raw, UTF_8), Payload.class);
            } catch (IOException e) {
                err().println("sideband hook: notification passed through: unreadable payload: " + e.getMessage());
                return null;
            }
            if (payload == null || payload.hookEventName() == null) {
                return null;
            }
            String event = payload.hookEventName();
            if (event.equals("UserPromptSubmit")) {
                String prompt = payload.prompt() == null ? "" : payload.prompt().stripLeading();
                boolean transport = prompt.startsWith(Handoffs.ENVELOPE_MARKER) || Prompt.isPushedEnvelope(prompt) || Prompt.isHostNotification(prompt);
                return transport ? "the prompt is a delivered envelope or a host notice, not the operator" : null;
            }
            boolean turnEnd = event.equals("Stop") || event.equals("Notification") && "idle_prompt".equals(payload.notificationType());
            if (!turnEnd) {
                return null;
            }
            Optional<Path> located = stateDirectory(home, payload.cwd());
            if (located.isEmpty() || !Files.isDirectory(located.get())) {
                return null;
            }
            Role role = agent != null ? agent : joined(located.get(), payload.sessionId()).orElse(null);
            if (role == null) {
                err().println("sideband hook: notification passed through: Sideband is not in use in session " + payload.sessionId());
                return null;
            }
            Attention.Verdict verdict = attention.atTurnEnd(located.get(), role);
            return verdict.wanted() ? null : verdict.reason();
        }

        /** The role whose recorded session is the calling one; empty when Sideband is not joined there. */
        private Optional<Role> joined(Path stateDirectory, @Nullable String sessionId) {
            if (sessionId == null || sessionId.isBlank()) {
                return Optional.empty();
            }
            return Arrays.stream(Role.values())
                    .filter(role -> sessions.load(stateDirectory, role).map(s -> sessionId.equals(s.id())).orElse(false))
                    .findFirst();
        }

        /** Runs the wrapped command with the payload on its stdin; its output goes wherever this hook's would. */
        private int forward(byte[] payload) {
            try {
                Process process = new ProcessBuilder("/bin/sh", "-c", run)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                try (var stdin = process.getOutputStream()) {
                    stdin.write(payload);
                }
                int exit = process.waitFor();
                if (exit != 0) {
                    err().println("sideband hook: notifier exited " + exit + ": " + run);
                }
            } catch (IOException e) {
                err().println("sideband hook: could not run the notifier: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err().println("sideband hook: interrupted while the notifier ran");
            }
            return ExitCode.OK;
        }

        private PrintWriter err() {
            return spec.commandLine().getErr();
        }
    }
}
