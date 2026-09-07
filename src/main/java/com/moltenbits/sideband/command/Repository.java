package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** The {@code --repo} option every command shares, resolved to the state directory. */
final class Repository {

    @Option(names = "--repo", hidden = true, description = "Override the repository resolved from the current directory")
    Path directory = Path.of(System.getProperty("user.dir"));

    Path stateDirectory(SidebandHome home) {
        return home.initialize(directory);
    }
}
