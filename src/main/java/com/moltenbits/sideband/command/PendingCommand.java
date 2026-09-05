package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
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

    @Option(names = "--role", required = true, description = "claude or codex")
    Role role;

    private final SidebandHome home;
    private final RecipientState recipients;
    private final ObjectMapper json;

    PendingCommand(SidebandHome home, RecipientState recipients, ObjectMapper json) {
        this.home = home;
        this.recipients = recipients;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Output.print(spec, json, recipients.pending(repository.stateDirectory(home), role));
        return ExitCode.OK;
    }
}
