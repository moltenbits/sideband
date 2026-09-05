package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.routing.Resolution;
import com.moltenbits.sideband.routing.Routing;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
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

    @Option(names = "--repo", description = "A directory inside the repository (default: current directory)")
    Path repository = Path.of(System.getProperty("user.dir"));

    @Option(names = "--via", required = true, description = "The client the human typed into: claude or codex")
    Role via;

    @Option(names = "--human", required = true, description = "The human's identifier, e.g. james")
    String human;

    @Option(names = "--body-file", description = "File holding the prompt; standard input is read when omitted")
    Path bodyFile;

    private final SidebandHome home;
    private final Journal journal;
    private final Routing routing;
    private final ObjectMapper json;

    CaptureHumanCommand(SidebandHome home, Journal journal, Routing routing, ObjectMapper json) {
        this.home = home;
        this.journal = journal;
        this.routing = routing;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        String body = Bodies.read(bodyFile);
        Resolution resolution = routing.resolve(body, via);
        Draft draft = Draft.humanInstruction(ParticipantId.human(human), via, resolution.to(), body);
        Path file = home.initialize(repository).resolve(Journal.FILE_NAME);
        Entry entry = journal.append(file, draft);
        spec.commandLine().getOut().println(json.writeValueAsString(entry));
        return ExitCode.OK;
    }
}
