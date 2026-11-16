package com.moltenbits.sideband.project;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A registered project: the name its agents are addressed by, unique in the journal and never
 * changed once registered, and where it is.
 *
 * @param name the name, as in {@code <name>/@astra}
 * @param path what identifies the project: the absolute Git common directory, or the
 *             working directory itself outside Git
 * @param root the directory whose client configuration ({@code .claude}, {@code .codex}) belongs to it
 */
public record Project(String name, Path path, Path root) {

    public Project {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(root, "root");
    }
}
