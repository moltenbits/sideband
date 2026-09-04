package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Appended;
import com.moltenbits.sideband.journal.Journal;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.context.annotation.Prototype;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Appends one body to the repository's journal and prints the byte range it occupies. */
@Command(name = "append", description = "Append one message body to the repository journal")
@Prototype
public class AppendCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--repo", description = "A directory inside the repository (default: current directory)")
    Path repository = Path.of(System.getProperty("user.dir"));

    @Option(names = "--body-file", description = "File holding the body; standard input is read when omitted")
    Path bodyFile;

    private final SidebandHome home;
    private final Journal journal;
    private final ObjectMapper json;

    AppendCommand(SidebandHome home, Journal journal, ObjectMapper json) {
        this.home = home;
        this.journal = journal;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        String body = readBody();
        if (body.isBlank()) {
            throw new IllegalArgumentException("the body is empty");
        }
        Path file = home.initialize(repository).resolve(Journal.FILE_NAME);
        Appended appended = journal.append(file, body);
        spec.commandLine().getOut().println(json.writeValueAsString(appended));
        return ExitCode.OK;
    }

    private String readBody() throws IOException {
        if (bodyFile != null) {
            return Files.readString(bodyFile, UTF_8);
        }
        return new String(System.in.readAllBytes(), UTF_8);
    }
}
