package com.moltenbits.sideband.command;

import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.config.Configs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.protocol.Role;
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
 * Journals a human's prompt verbatim, resolving its routing directive, and prints the entry
 * with the push outcome per recipient. The prompt entered through the calling client is
 * recorded with the human as author.
 */
@Command(name = "capture-human", description = "Journal a human prompt entered through a client, resolving its routing directive", mixinStandardHelpOptions = true)
@Prototype
public class CaptureHumanCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--via", hidden = true, description = "Override the client detected from the environment")
    Role via;

    @Option(names = "--human", hidden = true, description = "Override the configured human identifier")
    String human;

    @Option(names = "--body-file", description = "File holding the prompt; standard input is read when omitted")
    Path bodyFile;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final HumanCapture capture;
    private final Configs configs;
    private final ObjectMapper json;

    CaptureHumanCommand(SidebandHome home, HostEnvironment host, HumanCapture capture, Configs configs, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.capture = capture;
        this.configs = configs;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        String body = Bodies.read(bodyFile);
        Role client = via != null ? via : host.requireRole("--via");
        Path stateDirectory = repository.stateDirectory(home);
        if (human != null) {
            configs.initialize(stateDirectory, repository.directory, human);
        }
        Output.print(spec, json, capture.capture(stateDirectory, client, body));
        return ExitCode.OK;
    }
}
