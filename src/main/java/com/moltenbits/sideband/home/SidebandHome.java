package com.moltenbits.sideband.home;

import java.nio.file.Path;

/**
 * Locates the Sideband state directory for a working directory.
 * <p>
 * Inside a Git repository, state lives beneath Git's shared private metadata directory so
 * that every worktree sees the same journal while nothing is ever tracked. Outside one, it
 * lives in a {@code .sideband} directory in the working directory itself.
 */
public interface SidebandHome {

    /** The directory name created beneath the Git common directory. */
    String DIRECTORY_NAME = "sideband";

    /** The directory name created in a working directory that is not inside a repository. */
    String PLAIN_DIRECTORY_NAME = ".sideband";

    /** Resolves the state directory for {@code workingDirectory} without creating it. */
    Path locate(Path workingDirectory);

    /** Resolves the state directory and creates it, private to the current user, when absent. */
    Path initialize(Path workingDirectory);

    /** The directory whose client configuration ({@code .claude}, {@code .codex}) belongs to a state directory. */
    default Path projectRoot(Path stateDirectory) {
        if (stateDirectory.getFileName().toString().equals(PLAIN_DIRECTORY_NAME)) {
            return stateDirectory.getParent();
        }
        return stateDirectory.getParent().getParent();
    }
}
