package com.moltenbits.sideband.home;

import java.nio.file.Path;

/**
 * Locates the Sideband state directory for a repository.
 * <p>
 * State lives beneath Git's shared private metadata directory so that every
 * worktree of a repository sees the same journal while nothing is ever tracked.
 */
public interface SidebandHome {

    /** The directory name created beneath the Git common directory. */
    String DIRECTORY_NAME = "sideband";

    /**
     * Resolves the state directory for the repository containing {@code workingDirectory}
     * without creating it.
     *
     * @throws NotARepositoryException when the directory is not inside a Git repository
     */
    Path locate(Path workingDirectory);

    /**
     * Resolves the state directory and creates it, private to the current user, when absent.
     *
     * @throws NotARepositoryException when the directory is not inside a Git repository
     */
    Path initialize(Path workingDirectory);
}
