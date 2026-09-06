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
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Entry points a client host calls directly, never a person. */
@Command(name = "hook", description = "Entry points for client hooks", mixinStandardHelpOptions = true,
        subcommands = HookCommand.Prompt.class)
public class HookCommand {

    /**
     * Both clients' {@code UserPromptSubmit} hook. Reads the hook payload on stdin and journals
     * the prompt verbatim, attributed to the operator via the calling client, whenever that
     * client's role has joined in the repository. Delivered envelopes, slash commands, and
     * shell commands are never captured. Capture never blocks the prompt: any problem goes to
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
            String message = skillMessage(trimmed);
            if (message != null) {
                prompt = message; // the operator's words typed as the skill's argument: capture them, not the command
            } else if (isSkillCommand(trimmed) || trimmed.isBlank() || trimmed.startsWith(Handoffs.ENVELOPE_MARKER)
                    || trimmed.startsWith("/") || trimmed.startsWith("!")) {
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
            // Both hosts use the same event name and payload shape, so the client is known only
            // from the registered --agent or the shell's markers. Which conversation or process
            // is calling does not matter: the prompt belongs to whoever holds the role here.
            Role role = agent != null ? agent : host.role().orElse(null);
            if (role == null) {
                String reason = "cannot tell which client this is; register the hook with --agent claude or --agent codex";
                return anyActive(stateDirectory) ? failed(reason) : skipped(reason);
            }
            if (!isActive(stateDirectory, role)) {
                return inactive(stateDirectory, role);
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

        private boolean isActive(Path stateDirectory, Role role) {
            return sessions.load(stateDirectory, role).isPresent();
        }

        private boolean anyActive(Path stateDirectory) {
            return Arrays.stream(Role.values()).anyMatch(role -> isActive(stateDirectory, role));
        }

        private static String note(Captured captured) {
            String delivered = captured.pushes().stream()
                    .map(p -> p.role().id() + "=" + p.outcome().id())
                    .collect(Collectors.joining(", "));
            String id = captured.metadata().id();
            return delivered.isEmpty()
                    ? "Sideband recorded this prompt as " + id + ". Do not capture it again; cite it as --caused-by when delegating."
                    : "Sideband recorded this prompt as " + id + " and delivered it: " + delivered
                    + ". Do not capture or route it again; cite it as --caused-by when delegating.";
        }

        @Serdeable(naming = SnakeCaseStrategy.class)
        record Payload(@Nullable String prompt, @Nullable String cwd, @Nullable String hookEventName) {
        }

        @Serdeable
        record Response(HookOutput hookSpecificOutput) {
        }

        @Serdeable
        record HookOutput(String hookEventName, String additionalContext) {
        }
    }
}
