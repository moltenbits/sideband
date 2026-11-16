package com.moltenbits.sideband.project;

import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a working directory's project is, before anything is known about whether it is
 * registered: in Git, the absolute common directory, shared by every worktree, with its
 * parent as the root; outside Git, the working directory itself as both.
 *
 * @param path what identifies the project
 * @param root the directory whose name the project takes by default and whose client configuration belongs to it
 */
public record ProjectLocation(Path path, Path root) {

    public ProjectLocation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(root, "root");
    }

    /**
     * The name the project takes unless {@code init as} gives it another: the root
     * directory's name, lowercased, every run of other characters made one hyphen, with
     * anything before the first letter and any trailing hyphen dropped, and cut to the
     * longest a name may be. Null when nothing of the directory name is left.
     */
    public @Nullable String defaultName() {
        Path name = root.getFileName();
        return name == null ? null : normalize(name.toString());
    }

    static @Nullable String normalize(String directory) {
        String hyphenated = directory.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String trimmed = hyphenated.replaceFirst("^[^a-z]+", "");
        if (trimmed.length() > ParticipantId.NAME_LENGTH) {
            trimmed = trimmed.substring(0, ParticipantId.NAME_LENGTH);
        }
        trimmed = trimmed.replaceFirst("-+$", "");
        return ParticipantId.isName(trimmed) ? trimmed : null;
    }
}
