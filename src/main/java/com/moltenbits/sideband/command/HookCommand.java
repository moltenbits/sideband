package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.NotARepositoryException;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.PushOutcome;
import com.moltenbits.sideband.push.PushResult;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Session;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Entry points a client host calls directly, never a person. */
@Command(name = "hook", description = "Entry points for client hooks", mixinStandardHelpOptions = true,
        subcommands = HookCommand.Prompt.class)
public class HookCommand {

    /**
     * Claude Code's {@code UserPromptSubmit} hook. Reads the hook payload on stdin and journals
     * the prompt verbatim when this session owns the Claude cursor in the prompt's repository.
     * Delivered envelopes, slash commands, and shell commands are never captured. Capture
     * never blocks the prompt: any problem goes to stderr and the exit code is always 0.
     */
    @Command(name = "prompt", description = "Claude Code UserPromptSubmit hook: journal the human's prompt", mixinStandardHelpOptions = true)
    @Prototype
    public static class Prompt implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        private final SidebandHome home;
        private final RecipientState recipients;
        private final HumanCapture capture;
        private final ObjectMapper json;

        Prompt(SidebandHome home, RecipientState recipients, HumanCapture capture, ObjectMapper json) {
            this.home = home;
            this.recipients = recipients;
            this.capture = capture;
            this.json = json;
        }

        @Override
        public Integer call() throws IOException {
            Payload payload;
            try {
                payload = json.readValue(new String(System.in.readAllBytes(), UTF_8), Payload.class);
            } catch (IOException e) {
                spec.commandLine().getErr().println("sideband hook: unreadable payload: " + e.getMessage());
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
            Session session = recipients.load(stateDirectory, Role.CLAUDE).session();
            if (session == null || payload.sessionId() == null || !session.id().equals(payload.sessionId())) {
                return ExitCode.OK;
            }
            try {
                Captured captured = capture.capture(stateDirectory, Role.CLAUDE, prompt);
                Output.print(spec, json, new Response(new HookOutput("UserPromptSubmit", note(captured))));
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("sideband hook: capture failed: " + e.getMessage());
            }
            return ExitCode.OK;
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
        record Payload(@Nullable String prompt, @Nullable String cwd, @Nullable String sessionId) {
        }

        @Serdeable
        record Response(HookOutput hookSpecificOutput) {
        }

        @Serdeable
        record HookOutput(String hookEventName, String additionalContext) {
        }
    }
}
