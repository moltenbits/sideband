package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Activation;
import com.moltenbits.sideband.recipient.RecipientState;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Starts a Sideband session for a role: initializes state, sets the startup watermark,
 * and prints the backlog the human must decide on. The listener starts from
 * {@code session.watermark_end}.
 */
@Command(name = "activate", description = "Start a session for a role, establish its watermark, and list its backlog")
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
    private final RecipientState recipients;
    private final ObjectMapper json;

    ActivateCommand(SidebandHome home, HostEnvironment host, RecipientState recipients, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.recipients = recipients;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        String id = sessionId != null ? sessionId : host.sessionId(who).orElseThrow(() -> new IllegalArgumentException(
                "cannot tell the " + who.id() + " session id from the environment; pass --session-id"));
        Long pid = parentPid != null ? parentPid : host.parentPid(who).orElse(null);
        Activation activation = recipients.activate(repository.stateDirectory(home), who, id, pid, replace);
        Output.print(spec, json, activation);
        return ExitCode.OK;
    }
}
