package com.moltenbits.sideband.config;

import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

@Singleton
class FileConfigs implements Configs {

    private final ObjectMapper json;

    FileConfigs(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Optional<Config> load(Path stateDirectory) {
        Path file = stateDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Config config = json.readValue(Files.readString(file, UTF_8), Config.class);
            if (config.schema() != Config.SCHEMA) {
                throw new IllegalStateException(file + " has config schema " + config.schema() + "; this build reads " + Config.SCHEMA);
            }
            return Optional.of(config);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    @Override
    public Config initialize(Path stateDirectory, Path workingDirectory, @Nullable String humanId) {
        Optional<Config> existing = load(stateDirectory);
        if (existing.isPresent()) {
            return existing.get();
        }
        String gitName = gitUserName(workingDirectory);
        String id = humanId != null ? humanId : slug(gitName);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("no human identifier: pass --human or set git config user.name");
        }
        ParticipantId.human(id);
        String display = gitName != null && !gitName.isBlank() ? gitName.strip() : capitalize(id);
        Config config = new Config(Config.SCHEMA, new HumanIdentity(id, display));
        Path file = stateDirectory.resolve(FILE_NAME);
        try {
            Files.writeString(file, json.writeValueAsString(config) + "\n", UTF_8);
            try {
                Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // permissions cannot be tightened on this filesystem; version one accepts that
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
        return config;
    }

    @Override
    public HumanIdentity require(Path stateDirectory) {
        return load(stateDirectory).map(Config::human)
                .orElseThrow(() -> new IllegalArgumentException("no Sideband configuration in " + stateDirectory
                        + ": run `sideband init` first or pass --human"));
    }

    /** Lowercase ASCII letters, digits, and separators, e.g. "James Hardwick" becomes "james-hardwick". */
    static String slug(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
        return slug.isEmpty() ? null : slug;
    }

    private static String capitalize(String id) {
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    @Nullable
    private static String gitUserName(Path workingDirectory) {
        try {
            Process git = new ProcessBuilder(List.of("git", "config", "user.name"))
                    .directory(workingDirectory.toFile())
                    .start();
            String out = new String(git.getInputStream().readAllBytes(), UTF_8).strip();
            return git.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
