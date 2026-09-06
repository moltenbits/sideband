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
 * Everything a role has to look at, derived from the journal: unanswered requests to it,
 * informational entries it has not been shown, and its own requests still awaiting a reply.
 * This is where a listener's wake line sends the client, so the output starts with the
 * handling steps. Showing the report advances the role's read position past the updates.
 */
@Command(name = "pending", description = "List a role's unanswered requests, unseen updates, and unanswered outgoing requests", mixinStandardHelpOptions = true)
@Prototype
public class PendingCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--role", hidden = true, description = "Override the client detected from the environment")
    Role role;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Sessions sessions;
    private final Pending pending;
    private final ObjectMapper json;

    PendingCommand(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.sessions = sessions;
        this.pending = pending;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        Path stateDirectory = repository.stateDirectory(home);
        PendingReport report = pending.report(stateDirectory, who);
        if (report.session() != null) {
            sessions.advance(stateDirectory, who, report.end());
        }
        Output.print(spec, json, report);
        return ExitCode.OK;
    }
}
