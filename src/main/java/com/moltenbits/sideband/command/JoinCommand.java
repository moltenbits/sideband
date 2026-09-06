package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.session.Delivery;
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
 * The listener starts from {@code session.watermark}.
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

    @Option(names = "--deliver", paramLabel = "push|listen", description = "Claude only: how entries reach this session for the whole session. push: writers post them into this Claude Code session; listen: this session runs the Monitor. Default: push when doctor finds crossSessionInbound accepted, else listen")
    Delivery deliver;

    @Option(names = "--home", hidden = true, description = "Override the home directory whose Claude settings decide the default delivery")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Sessions sessions;
    private final Pending pending;
    private final Installer installer;
    private final ObjectMapper json;

    JoinCommand(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, Installer installer, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.sessions = sessions;
        this.pending = pending;
        this.installer = installer;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        String id = sessionId != null ? sessionId : host.sessionId(who).orElseThrow(() -> new IllegalArgumentException(
                "cannot tell the " + who.id() + " session id from the environment; pass --session-id"));
        Path stateDirectory = repository.stateDirectory(home);
        sessions.join(stateDirectory, who, id, resume, who == Role.CLAUDE ? delivery(stateDirectory) : null);
        PendingReport report = pending.report(stateDirectory, who);
        sessions.advance(stateDirectory, who, report.end());
        Output.print(spec, json, report);
        return ExitCode.OK;
    }

    /**
     * The mode is fixed here so that every later writer and this session agree. The default
     * follows the settings files doctor can read; managed settings and --settings are not among
     * them, so an operator whose session is held by those passes --deliver listen.
     */
    private Delivery delivery(Path stateDirectory) {
        if (deliver != null) {
            return deliver;
        }
        InstallReport.Item inbound = installer.inbound(homeDirectory, home.projectRoot(stateDirectory));
        return inbound.state().equals("installed") ? Delivery.PUSH : Delivery.LISTEN;
    }
}
