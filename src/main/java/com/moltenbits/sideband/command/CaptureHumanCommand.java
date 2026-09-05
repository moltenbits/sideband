package com.moltenbits.sideband.command;

import com.moltenbits.sideband.config.Configs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.Pushes;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Resolution;
import com.moltenbits.sideband.routing.Destination;
import com.moltenbits.sideband.routing.Routing;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Journals a human's prompt verbatim, resolving its routing directive, and prints the entry.
 * The prompt entered through {@code via} is recorded with the human as author.
 */
@Command(name = "capture-human", description = "Journal a human prompt entered through a client, resolving its routing directive")
@Prototype
public class CaptureHumanCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--via", hidden = true, description = "Override the client detected from the environment")
    Role via;

    @Option(names = "--human", description = "The human's identifier (default: the one recorded by `sideband init`)")
    String human;

    @Option(names = "--body-file", description = "File holding the prompt; standard input is read when omitted")
    Path bodyFile;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Journal journal;
    private final Routing routing;
    private final RecipientState recipients;
    private final Configs configs;
    private final Pushes pushes;
    private final ObjectMapper json;

    CaptureHumanCommand(SidebandHome home, HostEnvironment host, Journal journal, Routing routing, RecipientState recipients,
                        Configs configs, Pushes pushes, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.journal = journal;
        this.routing = routing;
        this.recipients = recipients;
        this.configs = configs;
        this.pushes = pushes;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        String body = Bodies.read(bodyFile);
        if (via == null) {
            via = host.requireRole("--via");
        }
        Destination destination = routing.resolve(body, via);
        Path stateDirectory = repository.stateDirectory(home);
        String humanId = human != null ? human : configs.require(stateDirectory).id();
        Draft draft = Draft.humanInstruction(ParticipantId.human(humanId), via, destination.to(), body);
        Entry entry = journal.append(stateDirectory.resolve(Journal.FILE_NAME), draft);
        if (destination.to().contains(ParticipantId.of(via))) {
            // The client the human typed into acts on this turn directly; it must never redeliver it.
            recipients.resolve(stateDirectory, via, List.of(entry.metadata().id()), Resolution.ORIGINATING_TURN);
        }
        Output.print(spec, json, Appended.of(entry, pushes.deliver(stateDirectory, entry)));
        return ExitCode.OK;
    }
}
