package com.moltenbits.sideband.project;

import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The projects registered in the journal, each by its location and its name, both unique.
 * Registration is the only write: a project is never renamed or removed.
 */
public interface Projects {

    /** The project registered at {@code location}, when one is. */
    Optional<Project> find(Path stateDirectory, ProjectLocation location);

    /** The project registered under {@code name}, when one is. */
    Optional<Project> named(Path stateDirectory, String name);

    /**
     * The project at {@code location}, registering it first when it is not: under
     * {@code name}, or its default name when {@code name} is null. A project already
     * registered keeps its name, and asking for another is refused.
     *
     * @throws IllegalArgumentException when the name is invalid, taken by another project, or
     *                                  differs from the one the project is registered under
     */
    Project register(Path stateDirectory, ProjectLocation location, @Nullable String name);
}
