package com.moltenbits.sideband.install;

import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Skills come from the executable's own resources; the hook entry is merged into settings JSON. */
@Singleton
class ResourceInstaller implements Installer {

    /** Skill source directory inside the resources, and where each client looks for it. */
    private static final Map<String, String> SKILLS = Map.of(
            "sideband-claude", ".claude/skills/sideband",
            "sideband-codex", ".agents/skills/sideband");
    static final String HOOK_COMMAND = "\"$HOME\"/.claude/skills/sideband/hooks/prompt.sh";
    static final String HOOK_EVENT = "UserPromptSubmit";
    private static final String SETTINGS = ".claude/settings.json";

    private final ObjectMapper json;

    ResourceInstaller(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public InstallReport install(Path homeDir, Path projectDir) {
        List<InstallReport.Item> skills = new ArrayList<>();
        for (String source : List.of("sideband-claude", "sideband-codex")) {
            skills.add(installSkill(source, homeDir.resolve(SKILLS.get(source))));
        }
        return new InstallReport(skills, installHook(projectDir.resolve(SETTINGS)));
    }

    @Override
    public InstallReport inspect(Path homeDir, Path projectDir) {
        List<InstallReport.Item> skills = new ArrayList<>();
        for (String source : List.of("sideband-claude", "sideband-codex")) {
            Path target = homeDir.resolve(SKILLS.get(source));
            skills.add(new InstallReport.Item(client(source), target.toString(), skillState(source, target)));
        }
        Path settings = projectDir.resolve(SETTINGS);
        return new InstallReport(skills, new InstallReport.Item("claude-prompt-hook", settings.toString(), hookState(settings)));
    }

    private InstallReport.Item installSkill(String source, Path target) {
        try {
            String before = skillState(source, target);
            if (before.equals("unchanged")) {
                return new InstallReport.Item(client(source), target.toString(), "unchanged");
            }
            if (Files.isSymbolicLink(target)) {
                Files.delete(target); // a development link from an earlier install recipe
            } else if (Files.exists(target) && !Files.isDirectory(target)) {
                return new InstallReport.Item(client(source), target.toString(), "conflict");
            }
            for (String file : files(source)) {
                Path destination = target.resolve(file);
                Files.createDirectories(destination.getParent());
                Files.write(destination, resource(source + "/" + file));
                if (file.endsWith(".sh") || file.startsWith("scripts/")) {
                    markExecutable(destination);
                }
            }
            return new InstallReport.Item(client(source), target.toString(), before.equals("missing") ? "installed" : "updated");
        } catch (IOException e) {
            throw new UncheckedIOException("could not install the " + client(source) + " skill into " + target, e);
        }
    }

    private String skillState(String source, Path target) {
        try {
            if (!Files.exists(target)) {
                return "missing";
            }
            if (Files.isSymbolicLink(target)) {
                return "stale";
            }
            if (!Files.isDirectory(target)) {
                return "conflict";
            }
            for (String file : files(source)) {
                Path installed = target.resolve(file);
                if (!Files.exists(installed) || !Arrays.equals(Files.readAllBytes(installed), resource(source + "/" + file))) {
                    return "stale";
                }
            }
            return "unchanged";
        } catch (IOException e) {
            return "unreadable";
        }
    }

    @SuppressWarnings("unchecked")
    private InstallReport.Item installHook(Path settings) {
        try {
            Map<String, Object> root = readSettings(settings);
            Map<String, Object> hooks = (Map<String, Object>) root.computeIfAbsent("hooks", k -> new LinkedHashMap<>());
            List<Object> event = (List<Object>) hooks.computeIfAbsent(HOOK_EVENT, k -> new ArrayList<>());
            String state = "added";
            for (Object matcher : event) {
                if (!(matcher instanceof Map<?, ?> m)) {
                    continue;
                }
                Object inner = m.get("hooks");
                if (!(inner instanceof List<?> commands)) {
                    continue;
                }
                for (Object command : commands) {
                    if (command instanceof Map<?, ?> c && String.valueOf(c.get("command")).contains("sideband")) {
                        if (HOOK_COMMAND.equals(c.get("command"))) {
                            return new InstallReport.Item("claude-prompt-hook", settings.toString(), "unchanged");
                        }
                        ((Map<String, Object>) c).put("command", HOOK_COMMAND);
                        state = "updated";
                    }
                }
            }
            if (state.equals("added")) {
                Map<String, Object> command = new LinkedHashMap<>();
                command.put("type", "command");
                command.put("command", HOOK_COMMAND);
                Map<String, Object> matcher = new LinkedHashMap<>();
                matcher.put("hooks", List.of(command));
                event.add(matcher);
            }
            Files.createDirectories(settings.getParent());
            Files.writeString(settings, PrettyJson.render(root) + "\n", UTF_8);
            return new InstallReport.Item("claude-prompt-hook", settings.toString(), state);
        } catch (IOException e) {
            throw new UncheckedIOException("could not update " + settings, e);
        }
    }

    private String hookState(Path settings) {
        try {
            if (!Files.exists(settings)) {
                return "missing";
            }
            String text = Files.readString(settings, UTF_8);
            if (text.contains(PrettyJson.quote(HOOK_COMMAND))) {
                return "installed";
            }
            return text.contains("sideband") ? "stale" : "missing";
        } catch (IOException e) {
            return "unreadable";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSettings(Path settings) throws IOException {
        if (!Files.exists(settings)) {
            return new LinkedHashMap<>();
        }
        String text = Files.readString(settings, UTF_8);
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = json.readValue(text, Map.class);
        return new LinkedHashMap<>(parsed);
    }

    private static List<String> files(String source) throws IOException {
        List<String> files = new ArrayList<>();
        for (String line : new String(resource("manifest.txt"), UTF_8).split("\n")) {
            if (line.startsWith(source + "/")) {
                files.add(line.substring(source.length() + 1));
            }
        }
        if (files.isEmpty()) {
            throw new IOException("no embedded files for skill " + source);
        }
        return files;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = ResourceInstaller.class.getResourceAsStream("/skills/" + path)) {
            if (in == null) {
                throw new IOException("missing embedded resource skills/" + path);
            }
            return in.readAllBytes();
        }
    }

    private static void markExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // no POSIX permissions on this filesystem
        }
    }

    private static String client(String source) {
        return source.substring("sideband-".length());
    }
}
