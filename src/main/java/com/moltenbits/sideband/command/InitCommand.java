package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
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

/**
 * Everything a repository needs before the first session: the private state directory,
 * both client skills installed from the executable, and the shared prompt
 * hook registered in both clients' project settings. Host trust is still required. Safe to rerun.
 */
@Command(name = "init", description = "Set up this repository: private state, both client skills, and the capture hook", mixinStandardHelpOptions = true)
@Prototype
public class InitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--skip-clients", description = "Only create the state directory")
    boolean skipClients;

    @Option(names = "--home", hidden = true, description = "Override the home directory the skills are installed under")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final SidebandHome home;
    private final Installer installer;
    private final ObjectMapper json;

    InitCommand(SidebandHome home, Installer installer, ObjectMapper json) {
        this.home = home;
        this.installer = installer;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = repository.stateDirectory(home);
        InstallReport clients = skipClients ? null : installer.install(homeDirectory, projectRoot(stateDirectory));
        Output.print(spec, json, new Result(stateDirectory.toString(), clients));
        return ExitCode.OK;
    }

    /**
     * Where the clients' project settings live: the working directory itself when the state
     * directory is a plain {@code .sideband} in it, otherwise the parent of the git directory
     * that holds {@code sideband}.
     */
    static Path projectRoot(Path stateDirectory) {
        return new SidebandHome() {
            @Override
            public Path locate(Path workingDirectory) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path initialize(Path workingDirectory) {
                throw new UnsupportedOperationException();
            }
        }.projectRoot(stateDirectory);
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Result(String stateDirectory, @Nullable InstallReport clients) {
    }
}
