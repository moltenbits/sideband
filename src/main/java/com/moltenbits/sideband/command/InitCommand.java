package com.moltenbits.sideband.command;

import com.moltenbits.sideband.config.Config;
import com.moltenbits.sideband.config.Configs;
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
 * Everything a repository needs before the first session: the private state directory and
 * configuration, both client skills installed from the executable, and the Claude Code
 * prompt hook registered in the repository's settings. Safe to rerun.
 */
@Command(name = "init", description = "Set up this repository: private state, configuration, both client skills, and the capture hook", mixinStandardHelpOptions = true)
@Prototype
public class InitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--human", description = "The human's identifier (default: a slug of git config user.name)")
    String human;

    @Option(names = "--skip-clients", description = "Only create the state directory and configuration")
    boolean skipClients;

    @Option(names = "--home", hidden = true, description = "Override the home directory the skills are installed under")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final SidebandHome home;
    private final Configs configs;
    private final Installer installer;
    private final ObjectMapper json;

    InitCommand(SidebandHome home, Configs configs, Installer installer, ObjectMapper json) {
        this.home = home;
        this.configs = configs;
        this.installer = installer;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = repository.stateDirectory(home);
        Config config = configs.initialize(stateDirectory, repository.directory, human);
        InstallReport clients = skipClients ? null : installer.install(homeDirectory, projectRoot(stateDirectory));
        Output.print(spec, json, new Result(stateDirectory.toString(), config, clients));
        return ExitCode.OK;
    }

    /** The state directory is {@code <git common dir>/sideband}; the project root is that git dir's parent. */
    static Path projectRoot(Path stateDirectory) {
        return stateDirectory.getParent().getParent();
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Result(String stateDirectory, Config config, @Nullable InstallReport clients) {
    }
}
