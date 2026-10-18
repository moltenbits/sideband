package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.project.Project;
import com.moltenbits.sideband.project.ProjectLocation;
import com.moltenbits.sideband.project.Projects;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Optional;

/** The {@code --repo} option every command shares, resolved to its project. */
final class Repository {

    @Option(names = "--repo", hidden = true, description = "Override the project resolved from the current directory")
    Path directory = Path.of(System.getProperty("user.dir"));

    /** The project, registered under its default name first when it is not yet. */
    Project project(SidebandHome home, Projects projects) {
        ProjectLocation location = home.locate(directory);
        return projects.find(home.stateDirectory(), location)
                .orElseGet(() -> projects.register(home.initialize(), location, null));
    }

    /** The project when it is registered; nothing is created. */
    Optional<Project> registered(SidebandHome home, Projects projects) {
        return projects.find(home.stateDirectory(), home.locate(directory));
    }
}
