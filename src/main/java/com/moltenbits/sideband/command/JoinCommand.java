package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.HeldPrompts;
import com.moltenbits.sideband.session.Sessions;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Joins the journal as this client, taking the role over from any earlier session, and
 * prints the first pending report. By default the
 * bookmark starts at the latest point, so only requests still unanswered are shown, every
 * one of them flagged for the operator's confirmation. With {@code --resume} the bookmark
 * starts where this role last left off, so everything written for it since is shown too,
 * and the waiting requests are flagged only when there are several: a lone one is acted on.
 * The listener starts from {@code session.watermark}. A prompt the hook held from this
 * session before the role joined is adopted first: journaled as the operator's words and
 * routed, so the words that made the client activate have an entry, reported as
 * {@code adopted}.
 */
@Command(name = "join", description = "Join the Sideband discussion as this client and list what is waiting; --resume picks up where you left off", mixinStandardHelpOptions = true)
@Prototype
public class JoinCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--role", hidden = true, description = "Override the client detected from the environment")
    Role role;

    @Option(names = "--session-id", hidden = true, description = "Override the session id detected from the environment")
    String sessionId;

    @Option(names = "--resume", description = "Start from where this client last left off, showing everything written for it since; otherwise start at the latest point")
    boolean resume;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Sessions sessions;
    private final Pending pending;
    private final HeldPrompts held;
    private final HumanCapture capture;
    private final ObjectMapper json;

    JoinCommand(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, HeldPrompts held,
                HumanCapture capture, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.sessions = sessions;
        this.pending = pending;
        this.held = held;
        this.capture = capture;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        String id = sessionId != null ? sessionId : host.sessionId(who).orElseThrow(() -> new IllegalArgumentException(
                "cannot tell the " + who.id() + " session id from the environment; pass --session-id"));
        Path stateDirectory = repository.stateDirectory(home);
        sessions.join(stateDirectory, who, id, resume);
        Captured adopted = held.take(stateDirectory, who, id)
                .map(prompt -> capture.capture(stateDirectory, who, prompt))
                .orElse(null);
        PendingReport report = pending.report(stateDirectory, who).withAdopted(adopted);
        sessions.advance(stateDirectory, who, report.end());
        Output.print(spec, json, report);
        return ExitCode.OK;
    }
}
