package com.moltenbits.sideband.home;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Resolves the state directory through {@code git rev-parse --git-common-dir}. */
@Singleton
class GitCommonDirHome implements SidebandHome {

    private static final List<String> REV_PARSE =
            List.of("git", "rev-parse", "--path-format=absolute", "--git-common-dir");

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    @Override
    public Path locate(Path workingDirectory) {
        try {
            Process git = new ProcessBuilder(REV_PARSE)
                    .directory(workingDirectory.toFile())
                    .start();
            String stdout = new String(git.getInputStream().readAllBytes(), UTF_8);
            String stderr = new String(git.getErrorStream().readAllBytes(), UTF_8);
            if (git.waitFor() != 0) {
                throw new NotARepositoryException(workingDirectory, stderr);
            }
            return Path.of(stdout.strip()).resolve(DIRECTORY_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException("could not run git in " + workingDirectory, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running git", e);
        }
    }

    @Override
    public Path initialize(Path workingDirectory) {
        Path directory = locate(workingDirectory);
        if (Files.isDirectory(directory)) {
            return directory;
        }
        try {
            try {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (UnsupportedOperationException noPosix) {
                Files.createDirectory(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + directory, e);
        }
        return directory;
    }
}
