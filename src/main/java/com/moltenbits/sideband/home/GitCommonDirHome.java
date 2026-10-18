package com.moltenbits.sideband.home;

import com.moltenbits.sideband.project.ProjectLocation;
import io.micronaut.context.annotation.Value;
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

/**
 * Keeps the state in {@code .sideband} under the home directory, {@code sideband.home-directory}
 * or the user's own, and locates a project through {@code git rev-parse --git-common-dir}, or,
 * outside any repository, as the working directory itself.
 */
@Singleton
class GitCommonDirHome implements SidebandHome {

    private static final List<String> REV_PARSE =
            List.of("git", "rev-parse", "--path-format=absolute", "--git-common-dir");

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path stateDirectory;

    GitCommonDirHome(@Value("${sideband.home-directory:}") String homeDirectory) {
        Path home = homeDirectory == null || homeDirectory.isBlank()
                ? Path.of(System.getProperty("user.home")) : Path.of(homeDirectory);
        this.stateDirectory = home.resolve(DIRECTORY_NAME);
    }

    @Override
    public Path stateDirectory() {
        return stateDirectory;
    }

    @Override
    public Path initialize() {
        if (Files.isDirectory(stateDirectory)) {
            return stateDirectory;
        }
        try {
            Files.createDirectories(stateDirectory.getParent());
            try {
                Files.createDirectory(stateDirectory, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (UnsupportedOperationException noPosix) {
                Files.createDirectory(stateDirectory);
            }
        } catch (IOException e) {
            if (Files.isDirectory(stateDirectory)) {
                return stateDirectory; // another command created it meanwhile
            }
            throw new UncheckedIOException("could not create " + stateDirectory, e);
        }
        return stateDirectory;
    }

    @Override
    public ProjectLocation locate(Path workingDirectory) {
        try {
            Process git = new ProcessBuilder(REV_PARSE)
                    .directory(workingDirectory.toFile())
                    .start();
            String stdout = new String(git.getInputStream().readAllBytes(), UTF_8);
            git.getErrorStream().readAllBytes();
            if (git.waitFor() != 0) {
                // Not a repository: the working directory is the project.
                Path directory = realPath(workingDirectory);
                return new ProjectLocation(directory, directory);
            }
            Path common = realPath(Path.of(stdout.strip()));
            return new ProjectLocation(common, common.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("could not run git in " + workingDirectory, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running git", e);
        }
    }

    private static Path realPath(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            return directory.toAbsolutePath().normalize();
        }
    }
}
