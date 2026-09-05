package com.moltenbits.sideband.command;

import com.moltenbits.sideband.config.Config;
import com.moltenbits.sideband.config.Configs;
import com.moltenbits.sideband.home.SidebandHome;
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
import java.util.concurrent.Callable;

/** Creates the private state directory and the local configuration if either is missing. */
@Command(name = "init", description = "Create the repository's private Sideband state and configuration", mixinStandardHelpOptions = true)
@Prototype
public class InitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--human", description = "The human's identifier (default: a slug of git config user.name)")
    String human;

    private final SidebandHome home;
    private final Configs configs;
    private final ObjectMapper json;

    InitCommand(SidebandHome home, Configs configs, ObjectMapper json) {
        this.home = home;
        this.configs = configs;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = repository.stateDirectory(home);
        Config config = configs.initialize(stateDirectory, repository.directory, human);
        Output.print(spec, json, new Result(stateDirectory.toString(), config));
        return ExitCode.OK;
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Result(String stateDirectory, Config config) {
    }
}
