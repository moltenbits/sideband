package com.moltenbits.sideband.install;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Skills come from the executable's own resources; the hook entry is merged into settings JSON. */
@Singleton
class ResourceInstaller implements Installer {

    /** Skill source directory inside the resources, and where each client looks for it. */
    private static final Map<String, String> SKILLS = Map.of(
            "sideband-claude", ".claude/skills/sideband",
            "sideband-codex", ".agents/skills/sideband");
    static final String HOOK_EVENT = "UserPromptSubmit";
    /** Only the stub is installed; everything else the skill needs comes from the executable. */
    private static final List<String> INSTALLED_FILES = List.of("SKILL.md");
    private static final String SETTINGS = ".claude/settings.json";

    private final ObjectMapper json;
    private final String hookCommand;

    @Inject
    ResourceInstaller(ObjectMapper json) {
        this(json, executablePath());
    }

    ResourceInstaller(ObjectMapper json, String executable) {
        this.json = json;
        this.hookCommand = "\"" + executable + "\" hook prompt";
    }

    /** The absolute path of the running executable, so the hook works whatever PATH the hook shell has. */
    static String executablePath() {
        return ProcessHandle.current().info().command().orElse("sideband");
    }

    @Override
    public String instructions(Role client) {
        try {
            return new String(resource("sideband-" + client.id() + "/INSTRUCTIONS.md"), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            } else if (Files.isDirectory(target)) {
                clear(target); // the directory is Sideband's; files an earlier version installed must not linger
            }
            for (String file : files(source)) {
                Path destination = target.resolve(file);
                Files.createDirectories(destination.getParent());
                Files.write(destination, resource(source + "/" + file));
            }
            return new InstallReport.Item(client(source), target.toString(), before.equals("missing") ? "installed" : "updated");
        } catch (IOException e) {
            throw new UncheckedIOException("could not install the " + client(source) + " skill into " + target, e);
        }
    }

    private static void clear(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) {
                    Files.delete(path);
                }
            }
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
            try (var listing = Files.list(target)) {
                if (listing.anyMatch(path -> !files(source).contains(path.getFileName().toString()))) {
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
                        if (hookCommand.equals(c.get("command"))) {
                            return new InstallReport.Item("claude-prompt-hook", settings.toString(), "unchanged");
                        }
                        ((Map<String, Object>) c).put("command", hookCommand);
                        state = "updated";
                    }
                }
            }
            if (state.equals("added")) {
                Map<String, Object> command = new LinkedHashMap<>();
                command.put("type", "command");
                command.put("command", hookCommand);
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
            if (text.contains(PrettyJson.quote(hookCommand))) {
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

    private static List<String> files(String source) {
        return INSTALLED_FILES;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = ResourceInstaller.class.getResourceAsStream("/skills/" + path)) {
            if (in == null) {
                throw new IOException("missing embedded resource skills/" + path);
            }
            return in.readAllBytes();
        }
    }

    private static String client(String source) {
        return source.substring("sideband-".length());
    }
}
