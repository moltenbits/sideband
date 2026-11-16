package com.moltenbits.sideband.home;

import com.moltenbits.sideband.project.ProjectLocation;

import java.nio.file.Path;

/**
 * Where Sideband keeps its state, and where a working directory's project is.
 * <p>
 * Every project's discussion lives in one journal, in a {@code .sideband} directory in the
 * user's home directory, so nothing is ever written into a project or tracked by it. A
 * project is located in Git through the shared common directory, so every worktree is the
 * same project, and outside Git by the working directory itself.
 */
public interface SidebandHome {

    /** The directory name created in the home directory. */
    String DIRECTORY_NAME = ".sideband";

    /** The state directory, without creating it. */
    Path stateDirectory();

    /** The state directory, created private to the current user when absent. */
    Path initialize();

    /** Where the project of {@code workingDirectory} is; nothing is created or registered. */
    ProjectLocation locate(Path workingDirectory);
}
