package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Journal;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** The {@code --repo} option every command shares, resolved to the state directory. */
final class Repository {

    @Option(names = "--repo", description = "A directory inside the repository (default: current directory)")
    Path directory = Path.of(System.getProperty("user.dir"));

    Path stateDirectory(SidebandHome home) {
        return home.initialize(directory);
    }

    Path journal(SidebandHome home) {
        return stateDirectory(home).resolve(Journal.FILE_NAME);
    }
}
