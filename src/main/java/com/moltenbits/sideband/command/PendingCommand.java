package com.moltenbits.sideband.command;

import com.moltenbits.sideband.handoff.Handling;
import com.moltenbits.sideband.handoff.Handoff;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.OutgoingState;
import com.moltenbits.sideband.recipient.Pending;
import com.moltenbits.sideband.recipient.RecipientState;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Lists what a role still has open: unresolved incoming entries and unanswered outgoing
 * requests. This is where a listener's wake line sends the client, so the output starts
 * with the handling steps and each entry is a full handoff.
 */
@Command(name = "pending", description = "List a role's unresolved incoming entries and unanswered requests", mixinStandardHelpOptions = true)
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
    private final Handoffs handoffs;
    private final ObjectMapper json;

    PendingCommand(SidebandHome home, HostEnvironment host, RecipientState recipients, Handoffs handoffs, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.recipients = recipients;
        this.handoffs = handoffs;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role who = role != null ? role : host.requireRole("--role");
        Path stateDirectory = repository.stateDirectory(home);
        Path file = stateDirectory.resolve(Journal.FILE_NAME);
        Pending pending = recipients.pending(stateDirectory, who);
        Output.print(spec, json, new Report(Handling.pending(who),
                handoffs.prepare(file, pending.backlog()), handoffs.prepare(file, pending.live()), pending.outgoing()));
        return ExitCode.OK;
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Report(String handling, List<Handoff> backlog, List<Handoff> live, Map<String, OutgoingState> outgoing) {
    }
}
