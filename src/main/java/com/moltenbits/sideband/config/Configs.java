package com.moltenbits.sideband.config;

import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Optional;

/** Reads and initializes the repository's local configuration. */
public interface Configs {

    String FILE_NAME = "config.json";

    Optional<Config> load(Path stateDirectory);

    /**
     * Writes the configuration when absent. The human identifier comes from {@code humanId}
     * when given, otherwise from a slug of {@code git config user.name}; the display name
     * is the git name when available.
     *
     * @return the configuration now on disk, new or pre-existing
     */
    Config initialize(Path stateDirectory, Path workingDirectory, @Nullable String humanId);

    /** The configured human, or a clear error telling the caller to run {@code init}. */
    HumanIdentity require(Path stateDirectory);
}
