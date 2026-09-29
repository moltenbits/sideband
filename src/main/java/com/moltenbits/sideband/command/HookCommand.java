package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.CaptureFailedException;
import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.handoff.Handling;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.pending.Attention;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.PushOutcome;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.session.HeldPrompts;
import com.moltenbits.sideband.session.InstanceRule;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * Moves the instance the calling conversation continues to it, by the hooks' rule of
     * REQUIREMENTS.md 9.5a. A client that starts a new conversation in place, as a clear does,
     * may keep the old one alive, and a push addressed to it would run there unseen; so
     * whenever the operator's own input arrives from a conversation the instance continues,
     * the record follows. Only the operator's input counts: a delivered envelope or a host
     * notice says nothing about where the operator is, and the callers never pass those here.
     */
    static InstanceRule.Followed follow(Sessions sessions, PrintWriter err, Path stateDirectory, Role role, String sessionId,
                                        @Nullable HostProcess caller) {
        InstanceRule.Followed followed = sessions.follow(stateDirectory, role, sessionId, caller);
        if (followed.moved()) {
            err.println("sideband hook: " + followed.instance() + " now delivers to " + sessionId);
        }
        return followed;
    }

    /** What the operator is told when a conversation could continue several instances and none can be told apart. */
    static String ambiguous(Role role, InstanceRule.Followed followed) {
        String invocation = Handling.invocation(role);
        return "Sideband cannot tell which " + role.displayName() + " instance this conversation continues: "
                + followed.candidates().stream().map(ParticipantId::value).collect(Collectors.joining(", "))
                + ". Tell the user; " + invocation + " joins as " + role.id() + ", and " + invocation
                + " as <name> joins as " + role.id() + ":<name>.";
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
     * Both clients' {@code SessionStart} hook, registered for the {@code clear} source, and in
     * Claude Code for {@code compact} too. A clear replaces the conversation on screen with a
     * new one before any prompt is typed, so the prompt hook cannot move the instance until
     * the operator speaks; this hook moves it at once and tells the new conversation that
     * Sideband is live in it. A compaction keeps the conversation but its skills only within a
     * budget, so the hook tells it again; it moves nothing, since an automatic compaction is
     * not the operator's input, and names only an instance the conversation already is. Every
     * other source keeps the conversation the instance is in, or is a new client whose first
     * prompt will claim an instance through the prompt hook. Never blocks the host: any problem
     * goes to stderr and the exit code is always 0.
     */
    @Command(name = "session-start", description = "Claude Code/Codex SessionStart hook: after a clear, move the instance it continues to the new conversation; after a compaction, remind it how entries arrive", mixinStandardHelpOptions = true)
    @Prototype
    public static class SessionStart implements Callable<Integer> {

        static final String EVENT = "SessionStart";
        static final String CLEAR = "clear";
        static final String COMPACT = "compact";
        /** What a conversation that may no longer hold the skill's instructions most needs to keep doing. */
        static final String DELIVERY = "Entries addressed to it arrive in this conversation on their own: never poll with"
                + " `sideband pending` to see whether anything arrived, or block waiting for a reply, and when nothing is left"
                + " for you until one comes, end the turn.";

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
                return announce();
            } catch (IOException | RuntimeException e) {
                spec.commandLine().getErr().println("sideband hook: session start ignored: " + e.getMessage());
                return ExitCode.OK;
            }
        }

        private int announce() throws IOException {
            Payload payload;
            try {
                payload = json.readValue(new String(System.in.readAllBytes(), UTF_8), Payload.class);
            } catch (IOException e) {
                return skipped("unreadable payload: " + e.getMessage());
            }
            if (payload == null || (payload.hookEventName() != null && !payload.hookEventName().equals(EVENT))) {
                return ExitCode.OK;
            }
            boolean compacted = COMPACT.equals(payload.source());
            if (!CLEAR.equals(payload.source()) && !compacted) {
                return skipped("source " + payload.source() + " keeps the conversation the instance is in");
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
            HostProcess caller = host.process(role).orElse(null);
            ParticipantId instance;
            if (compacted) {
                InstanceRule.Identified identified = sessions.identify(stateDirectory.get(), role, payload.sessionId(), caller);
                instance = identified.records().isEmpty() ? null : identified.instance();
            } else {
                InstanceRule.Followed followed = follow(sessions, spec.commandLine().getErr(), stateDirectory.get(), role,
                        payload.sessionId(), caller);
                if (followed.instance() == null && !followed.candidates().isEmpty()) {
                    Output.print(spec, json, new Response(new HookOutput(EVENT, ambiguous(role, followed))));
                    return ExitCode.OK;
                }
                instance = followed.instance();
            }
            if (instance == null) {
                return skipped("this conversation continues no " + role.id() + " instance here");
            }
            // The conversation may remember nothing, so an acknowledged request counts as
            // much as an open one: only the memory of taking it up was lost.
            int waiting = pending.report(stateDirectory.get(), instance).unfinished();
            String context = "Sideband is joined as " + instance.displayName() + " in this repository and delivers to this conversation"
                    + (waiting == 0 ? "" : "; " + waiting + (waiting == 1 ? " entry" : " entries") + " addressed to "
                    + instance.displayName() + " " + (waiting == 1 ? "is" : "are") + " waiting")
                    + ". " + DELIVERY + " " + Handling.invocation(role) + " has the handling instructions.";
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
     * the prompt verbatim, attributed to the operator via the calling instance, whenever the
     * calling session holds an instance here (REQUIREMENTS.md 9.5a); otherwise it holds the
     * prompt for the session's join. Delivered envelopes, slash commands, shell
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
        private final HeldPrompts held;
        private final ObjectMapper json;

        Prompt(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, HumanCapture capture,
               HeldPrompts held, ObjectMapper json) {
            this.home = home;
            this.host = host;
            this.sessions = sessions;
            this.pending = pending;
            this.capture = capture;
            this.held = held;
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
            // from the registered --agent or the shell's markers; which instance of it is calling
            // comes from the payload's session and the host's process (REQUIREMENTS.md 9.5a).
            Role role = agent != null ? agent : host.role().orElse(null);
            if (role == null) {
                if (!capturable) {
                    return ExitCode.OK;
                }
                String reason = "cannot tell which client this is; register the hook with --agent claude or --agent codex";
                return anyActive(stateDirectory) ? failed(reason) : skipped(reason);
            }
            String sessionId = payload.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                if (!capturable) {
                    return ExitCode.OK;
                }
                String reason = "cannot tell which " + role.id() + " instance this is: the hook payload names no session";
                return sessions.records(stateDirectory, role).isEmpty() ? skipped(reason) : failed(reason);
            }
            if (isJoinInvocation(trimmed)) {
                // The join that follows names the instance itself; inferring one first would move
                // another instance here, only for the join to release it from this session.
                return dropped(stateDirectory, role, sessionId);
            }
            // The operator typed this, so this is the conversation the operator is looking at:
            // even a prompt that is not recorded moves the instance there.
            InstanceRule.Followed followed = follow(sessions, spec.commandLine().getErr(), stateDirectory, role, sessionId,
                    host.process(role).orElse(null));
            if (followed.instance() == null) {
                return capturable ? hold(stateDirectory, role, sessionId, prompt, followed) : dropped(stateDirectory, role, sessionId);
            }
            // The session holds an instance now, so whatever it held from before no longer leads
            // to a join: a later one must not adopt a task the operator has since moved past.
            dropped(stateDirectory, role, sessionId);
            if (!capturable) {
                return ExitCode.OK;
            }
            Captured captured = capture.capture(stateDirectory, followed.instance(), prompt);
            Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit", note(captured))));
            return ExitCode.OK;
        }

        /** A prompt that was never meant to be captured, or a repository where Sideband is not in use: stderr only. */
        /** The skill's own argument words; anything else after {@code /sideband} or {@code $sideband} is a message. */
        private static final Set<String> SKILL_WORDS = Set.of("help", "status", "pending", "off");

        /**
         * The word that joins as a named instance. It is reserved together with whatever follows
         * it: a missing, invalid, or extra argument is a usage error for the model to report,
         * never a message (REQUIREMENTS.md 7.1).
         */
        private static final String AS = "as";

        /**
         * The operator's words when the prompt is the Sideband skill invoked with a message,
         * such as {@code /sideband @codex look at this}; null for any other prompt, including
         * the skill alone, with one of its own argument words, or with {@code as} and whatever follows.
         */
        static String skillMessage(String trimmed) {
            String rest = skillArgument(trimmed);
            return rest == null || reserved(rest) ? null : rest;
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

        /** The skill invoked alone, with one of its own words, or with {@code as <name>}: a command for the model, never the operator's words. */
        static boolean isSkillCommand(String trimmed) {
            String rest = skillArgument(trimmed);
            return rest != null && reserved(rest);
        }

        /** The skill alone, one of its own words, or {@code as} with whatever follows it. */
        private static boolean reserved(String rest) {
            return rest.isEmpty() || SKILL_WORDS.contains(rest.toLowerCase(Locale.ROOT)) || firstWord(rest).equals(AS);
        }

        /** The skill alone or with {@code as}: the operator is joining, and names the instance by doing so. */
        static boolean isJoinInvocation(String trimmed) {
            String rest = skillArgument(trimmed);
            return rest != null && (rest.isEmpty() || firstWord(rest).equals(AS));
        }

        /** The argument's first word, lowercased, split on whitespace as the rest of the parser splits it. */
        private static String firstWord(String rest) {
            int end = 0;
            while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
                end++;
            }
            return rest.substring(0, end).toLowerCase(Locale.ROOT);
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
         * The session holds no instance, so the prompt cannot be journaled yet; but it may be
         * the words that make the client activate, and the join they lead to adopts it. The
         * hook holds it for the session it was typed into, the latest such prompt replacing
         * any earlier one of that session's, and the model hears nothing beyond what an
         * inactive session is told. A hold that fails is a prompt lost as it always was,
         * reported on stderr only: the model is not told the prompt was not recorded, because
         * nothing was recording.
         */
        private int hold(Path stateDirectory, Role role, String sessionId, String prompt, InstanceRule.Followed followed)
                throws IOException {
            try {
                held.hold(stateDirectory, role, sessionId, prompt);
                spec.commandLine().getErr().println("sideband hook: held for join by " + role.id() + " in session " + sessionId);
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("sideband hook: could not hold the prompt for join: " + e.getMessage());
            }
            return followed.candidates().isEmpty() ? inactive(stateDirectory, role) : report(ambiguous(role, followed));
        }

        /** The operator's input was a command, not their words: whatever this session held no longer leads to its join. */
        private int dropped(Path stateDirectory, Role role, String sessionId) {
            try {
                held.drop(stateDirectory, role, sessionId);
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("sideband hook: could not drop the held prompt: " + e.getMessage());
            }
            return ExitCode.OK;
        }

        /**
         * Sideband is not active for the calling session, which is normal in a repository that
         * has it installed but is not using it right now. The model hears about it only when
         * nobody holds the client's unnamed instance and entries addressed to it are waiting,
         * so nothing sits unread in silence; when another session holds it, those entries are
         * that session's to handle, and saying so here would only invite a takeover.
         */
        private int inactive(Path stateDirectory, Role role) throws IOException {
            ParticipantId unnamed = ParticipantId.of(role);
            if (sessions.load(stateDirectory, unnamed).isPresent()) {
                return skipped("this session holds no " + role.id() + " instance, and " + unnamed + " is joined elsewhere");
            }
            int waiting = pending.report(stateDirectory, unnamed).waiting();
            if (waiting == 0) {
                return skipped("Sideband is not joined as " + role.id());
            }
            spec.commandLine().getErr().println("sideband hook: capture skipped: Sideband is not joined as " + role.id()
                    + "; " + waiting + " waiting");
            return report("Sideband is not active in this session and " + waiting + (waiting == 1 ? " entry" : " entries")
                    + " addressed to " + role.displayName() + " " + (waiting == 1 ? "is" : "are")
                    + " waiting. Tell the user; " + Handling.invocation(role) + " joins and reviews them.");
        }

        private boolean anyActive(Path stateDirectory) {
            return !sessions.all(stateDirectory).isEmpty();
        }

        private static String note(Captured captured) {
            String delivered = captured.pushes().stream()
                    .filter(p -> p.outcome() != PushOutcome.LISTENER_DELIVERS)
                    .map(p -> p.recipient().value() + "=" + p.outcome().id())
                    .collect(Collectors.joining(", "));
            String id = captured.metadata().id();
            return delivered.isEmpty()
                    ? "Sideband recorded this prompt as " + id + ". Do not capture it again; cite it as --caused-by when delegating."
                    : "Sideband recorded this prompt as " + id + " and delivered it: " + delivered
                    + ". Do not capture or route it again; cite it as --caused-by when delegating.";
        }
    }

    /**
     * The gate a host's notifier consults before it rings: reads the hook payload and exits 0
     * to let the notification through or 1 to hold it. The host fires its Stop hook at the end
     * of every turn, and a pushed envelope starts a turn like a typed prompt does, so without
     * this every exchange between the clients rings. The verdict comes from {@link Attention},
     * and the host's idle reminder, which repeats a turn end already decided, is held outright.
     * Everything that is not a turn end passes, a permission prompt above all. On a prompt
     * event, where the notifier's command is usually its dismiss, the operator's own words
     * pass and a delivered envelope does not, so an envelope never clears a notification the
     * operator has not seen. A session Sideband is not joined in, a repository without
     * Sideband, or a payload that cannot be read all pass: the client alone behaves as it
     * would without Sideband. Nothing is written to stdout; the reason goes to stderr.
     */
    @Command(name = "notify", description = "The gate for a host's notifier, on Stop, Notification, PermissionRequest, or UserPromptSubmit: exit 0 to let it ring, 1 to hold it", mixinStandardHelpOptions = true)
    @Prototype
    public static class Notify implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--agent", description = "The client whose hook this is, claude or codex; without it the payload's session id says which joined instance is calling")
        Role agent;

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
            String reason;
            try {
                reason = held(new String(System.in.readAllBytes(), UTF_8));
            } catch (IOException | RuntimeException e) {
                err().println("sideband hook: notification passed: " + e.getMessage());
                return ExitCode.OK;
            }
            if (reason == null) {
                return ExitCode.OK;
            }
            err().println("sideband hook: notification held: " + reason);
            return ExitCode.HELD;
        }

        /** The reason to hold the notification, or null to let it through. */
        private @Nullable String held(String raw) {
            Payload payload;
            try {
                payload = json.readValue(raw, Payload.class);
            } catch (IOException e) {
                err().println("sideband hook: notification passed: unreadable payload: " + e.getMessage());
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
            boolean idle = event.equals("Notification") && "idle_prompt".equals(payload.notificationType());
            if (!event.equals("Stop") && !idle) {
                return null;
            }
            Optional<Path> located = stateDirectory(home, payload.cwd());
            if (located.isEmpty() || !Files.isDirectory(located.get())) {
                return null;
            }
            ParticipantId instance = agent != null
                    ? sessions.identify(located.get(), agent, payload.sessionId(), null).instance()
                    : joined(located.get(), payload.sessionId()).orElse(null);
            if (instance == null) {
                err().println("sideband hook: notification passed: Sideband is not in use in session " + payload.sessionId());
                return null;
            }
            if (idle) {
                // The host's reminder that a turn ended a while ago with no input since: the turn end
                // itself already rang or was held, so under Sideband the reminder never adds a second.
                return "an idle reminder repeats a turn end that has already been decided";
            }
            Attention.Verdict verdict = attention.atTurnEnd(located.get(), instance);
            if (verdict.wanted()) {
                err().println("sideband hook: notification passed: " + verdict.reason());
                return null;
            }
            return verdict.reason();
        }

        /** The instance whose recorded session is the calling one; empty when Sideband is not joined there. */
        private Optional<ParticipantId> joined(Path stateDirectory, @Nullable String sessionId) {
            if (sessionId == null || sessionId.isBlank()) {
                return Optional.empty();
            }
            return sessions.all(stateDirectory).entrySet().stream()
                    .filter(record -> sessionId.equals(record.getValue().id()))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        private PrintWriter err() {
            return spec.commandLine().getErr();
        }
    }
}
