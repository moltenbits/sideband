package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.Role;
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

/** Lists what a role still has open: unresolved incoming entries and unanswered outgoing requests. */
@Command(name = "pending", description = "List a role's unresolved incoming entries and unanswered requests")
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
    private final RecipientState recipients;
    private final ObjectMapper json;

    PendingCommand(SidebandHome home, HostEnvironment host, RecipientState recipients, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.recipients = recipients;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        Output.print(spec, json, recipients.pending(repository.stateDirectory(home), who));
        return ExitCode.OK;
    }
}
