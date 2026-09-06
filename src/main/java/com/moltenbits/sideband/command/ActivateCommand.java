package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.protocol.Role;
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
 * Starts a Sideband session for a role and prints its first pending report. Everything
 * already in the journal is marked as predating the session, so the human confirms it
 * before anything actionable is acted on. The listener starts from {@code session.watermark}.
 */
@Command(name = "activate", description = "Start a session for a role and list what is waiting for it", mixinStandardHelpOptions = true)
@Prototype
public class ActivateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--role", hidden = true, description = "Override the client detected from the environment")
    Role role;

    @Option(names = "--session-id", hidden = true, description = "Override the session id detected from the environment")
    String sessionId;

    @Option(names = "--parent-pid", hidden = true, description = "Override the client process detected from the environment")
    Long parentPid;

    @Option(names = "--replace", description = "Supersede a live session that already owns the role")
    boolean replace;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Sessions sessions;
    private final Pending pending;
    private final ObjectMapper json;

    ActivateCommand(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.sessions = sessions;
        this.pending = pending;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        String id = sessionId != null ? sessionId : host.sessionId(who).orElseThrow(() -> new IllegalArgumentException(
                "cannot tell the " + who.id() + " session id from the environment; pass --session-id"));
        Long pid = parentPid != null ? parentPid : host.parentPid(who).orElse(null);
        Path stateDirectory = repository.stateDirectory(home);
        sessions.activate(stateDirectory, who, id, pid, replace);
        PendingReport report = pending.report(stateDirectory, who);
        sessions.advance(stateDirectory, who, report.end());
        Output.print(spec, json, report);
        return ExitCode.OK;
    }
}
