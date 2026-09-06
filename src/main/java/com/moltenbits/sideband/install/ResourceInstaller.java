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
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Skills come from the executable's own resources; the hook entry is merged into settings JSON. */
@Singleton
class ResourceInstaller implements Installer {

    /** Skill source directory inside the resources, and where each client looks for it. */
    private static final Map<String, String> SKILLS = Map.of(
            "claude", ".claude/skills/sideband",
            "codex", ".agents/skills/sideband");
    static final String HOOK_EVENT = "UserPromptSubmit";
    /** Only the stub is installed; everything else the skill needs comes from the executable. */
    private static final List<String> INSTALLED_FILES = List.of("SKILL.md");
    /** Marks a SKILL.md the operator ejected; the installer never overwrites one. */
    static final String EJECTED_MARKER = "<!-- ejected from sideband: edit freely; `sideband init` leaves this file alone and it no longer updates with the executable. Delete it and rerun `sideband init` to go back. -->";
    private static final String SETTINGS = ".claude/settings.json";
    private static final String CODEX_SETTINGS = ".codex/hooks.json";
    private static final Pattern AGENT_OVERRIDE = Pattern.compile("\\s+--agent(?:=|\\s+)(codex|claude)$");

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
            return new String(resource(client.id() + "/INSTRUCTIONS.md"), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InstallReport.Item eject(Path homeDir, Role client, boolean force) {
        String source = client.id();
        Path target = homeDir.resolve(SKILLS.get(source));
        if (!force && skillState(source, target).equals("ejected")) {
            throw new IllegalArgumentException("the " + client.displayName() + " skill at " + target.resolve("SKILL.md")
                    + " is already ejected and may hold your edits; pass --force to overwrite it");
        }
        try {
            String stub = new String(resource(source + "/SKILL.md"), UTF_8);
            int close = stub.indexOf("\n---\n", 4);
            if (!stub.startsWith("---\n") || close < 0) {
                throw new IOException("the embedded " + source + " stub has no front matter");
            }
            String frontMatter = stub.substring(0, close + "\n---\n".length());
            if (Files.isSymbolicLink(target)) {
                Files.delete(target);
            } else if (Files.exists(target) && !Files.isDirectory(target)) {
                return new InstallReport.Item(client.id(), target.toString(), "conflict");
            }
            Files.createDirectories(target);
            Files.writeString(target.resolve("SKILL.md"), frontMatter + "\n" + EJECTED_MARKER + "\n\n" + instructions(client), UTF_8);
            return new InstallReport.Item(client.id(), target.toString(), "ejected");
        } catch (IOException e) {
            throw new UncheckedIOException("could not eject the " + client.id() + " skill into " + target, e);
        }
    }

    @Override
    public InstallReport install(Path homeDir, Path projectDir) {
        List<InstallReport.Item> skills = new ArrayList<>();
        for (String source : List.of("claude", "codex")) {
            skills.add(installSkill(source, homeDir.resolve(SKILLS.get(source))));
        }
        return new InstallReport(skills, installHook(projectDir.resolve(SETTINGS), "claude-prompt-hook", Role.CLAUDE),
                installHook(projectDir.resolve(CODEX_SETTINGS), "codex-prompt-hook", Role.CODEX));
    }

    @Override
    public InstallReport inspect(Path homeDir, Path projectDir) {
        List<InstallReport.Item> skills = new ArrayList<>();
        for (String source : List.of("claude", "codex")) {
            Path target = homeDir.resolve(SKILLS.get(source));
            skills.add(new InstallReport.Item(client(source), target.toString(), skillState(source, target)));
        }
        Path settings = projectDir.resolve(SETTINGS);
        Path codexSettings = projectDir.resolve(CODEX_SETTINGS);
        return new InstallReport(skills, new InstallReport.Item("claude-prompt-hook", settings.toString(), hookState(settings, Role.CLAUDE)),
                new InstallReport.Item("codex-prompt-hook", codexSettings.toString(), hookState(codexSettings, Role.CODEX)));
    }

    private InstallReport.Item installSkill(String source, Path target) {
        try {
            String before = skillState(source, target);
            if (before.equals("unchanged") || before.equals("ejected")) {
                return new InstallReport.Item(client(source), target.toString(), before);
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
            Path stub = target.resolve("SKILL.md");
            if (Files.exists(stub) && Files.readString(stub, UTF_8).contains(EJECTED_MARKER)) {
                return "ejected";
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
    /**
     * Registers {@code hook prompt --agent <client>}: both clients send the same payload and
     * Codex gives hook shells no environment markers, so the registration itself names the
     * caller. An older registration, bare or naming the other client, is rewritten.
     */
    private InstallReport.Item installHook(Path settings, String name, Role client) {
        String registration = hookCommand + " --agent " + client.id();
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
                    if (command instanceof Map<?, ?> c && isSidebandHook(String.valueOf(c.get("command")))) {
                        if (registration.equals(c.get("command"))) {
                            return new InstallReport.Item(name, settings.toString(), "unchanged");
                        }
                        ((Map<String, Object>) c).put("command", registration);
                        state = "updated";
                    }
                }
            }
            if (state.equals("added")) {
                Map<String, Object> command = new LinkedHashMap<>();
                command.put("type", "command");
                command.put("command", registration);
                Map<String, Object> matcher = new LinkedHashMap<>();
                matcher.put("hooks", List.of(command));
                event.add(matcher);
            }
            Files.createDirectories(settings.getParent());
            Files.writeString(settings, PrettyJson.render(root) + "\n", UTF_8);
            return new InstallReport.Item(name, settings.toString(), state);
        } catch (IOException e) {
            throw new UncheckedIOException("could not update " + settings, e);
        }
    }

    private boolean isSidebandHook(String command) {
        command = AGENT_OVERRIDE.matcher(command).replaceFirst("");
        return command.equals(hookCommand)
                || command.matches("[\\\"']?(?:[^\\r\\n]*[/\\\\])?sideband(?:\\.exe)?[\\\"']?\\s+hook\\s+prompt")
                || command.endsWith("/skills/claude/hooks/prompt.sh") || command.endsWith("/skills/sideband-claude/hooks/prompt.sh");
    }

    private String hookState(Path settings, Role client) {
        try {
            if (!Files.exists(settings)) {
                return "missing";
            }
            String text = Files.readString(settings, UTF_8);
            if (text.contains(PrettyJson.quote(hookCommand + " --agent " + client.id()))) {
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
        return source;
    }
}
