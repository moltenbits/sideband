package com.moltenbits.sideband.install

import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class ResourceInstallerSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Installer installer = new ResourceInstaller(context.getBean(io.micronaut.serde.ObjectMapper), "/opt/sideband/bin/sideband")
    Path home = Files.createTempDirectory("home")
    Path project = Files.createTempDirectory("project")

    void "the component is exposed only through its interface"() {
        expect:
        context.getBean(Installer) instanceof ResourceInstaller
    }

    void "the embedded instructions are the checked-in ones and the stubs defer to the executable"() {
        expect:
        installer.instructions(com.moltenbits.sideband.protocol.Role.CLAUDE) == Files.readString(Path.of("skills/claude/INSTRUCTIONS.md"))
        installer.instructions(com.moltenbits.sideband.protocol.Role.CODEX) == Files.readString(Path.of("skills/codex/INSTRUCTIONS.md"))
        Files.readString(Path.of("skills/claude/SKILL.md")).contains("Run `sideband skill`")
        Files.readString(Path.of("skills/codex/SKILL.md")).contains("Run `sideband skill`")
    }

    void "ejecting writes the full instructions under the stub's front matter, and install then leaves it alone"() {
        given:
        installer.install(home, project)
        Path skill = home.resolve(".claude/skills/sideband/SKILL.md")

        when:
        InstallReport.Item ejected = installer.eject(home, com.moltenbits.sideband.protocol.Role.CLAUDE, false)
        String text = Files.readString(skill)

        then:
        ejected.state() == "ejected"
        ejected.path() == skill.parent.toString()
        text.startsWith("---\nname: sideband\n")
        text.contains("\n---\n\n" + ResourceInstaller.EJECTED_MARKER + "\n\n# Sideband (Claude Code adapter)")
        text.endsWith(installer.instructions(com.moltenbits.sideband.protocol.Role.CLAUDE))
        !text.contains("Run `sideband skill`")

        and: "a rerun of install reports it ejected and does not touch it; the other skill is untouched too"
        installer.install(home, project).skills()*.state() == ["ejected", "unchanged"]
        Files.readString(skill) == text
        installer.inspect(home, project).skills()*.state() == ["ejected", "unchanged"]

        and: "deleting it and reinstalling restores the stub"
        Files.delete(skill)
        installer.install(home, project).skills()*.state() == ["updated", "unchanged"]
        Files.readString(skill).contains("Run `sideband skill`")
    }

    void "ejecting over an ejected skill is refused unless forced, so the operator's edits survive"() {
        given:
        installer.install(home, project)
        installer.eject(home, role, false)
        Path skill = home.resolve(path)
        String edited = Files.readString(skill).replace("# Sideband", "# My Sideband") + "\nLocal rule: always say hello.\n"
        Files.writeString(skill, edited)

        when:
        installer.eject(home, role, false)

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("already ejected")
        e.message.contains("--force")
        Files.readString(skill) == edited

        when:
        InstallReport.Item forced = installer.eject(home, role, true)

        then:
        forced.state() == "ejected"
        Files.readString(skill) != edited
        Files.readString(skill).endsWith(installer.instructions(role))

        where:
        role                                          | path
        com.moltenbits.sideband.protocol.Role.CLAUDE  | ".claude/skills/sideband/SKILL.md"
        com.moltenbits.sideband.protocol.Role.CODEX   | ".agents/skills/sideband/SKILL.md"
    }

    void "a fresh install writes both stubs and registers each client's hook against this executable, naming the client"() {
        when:
        InstallReport report = installer.install(home, project)

        then:
        report.skills()*.state() == ["installed", "installed"]
        report.skills()*.name() == ["claude", "codex"]
        report.hook().state() == "added"
        report.codexHook().state() == "added"
        report.codexHook().name() == "codex-prompt-hook"
        Files.readString(home.resolve(".claude/skills/sideband/SKILL.md")) == Files.readString(Path.of("skills/claude/SKILL.md"))
        Files.readString(home.resolve(".agents/skills/sideband/SKILL.md")) == Files.readString(Path.of("skills/codex/SKILL.md"))
        Files.list(home.resolve(".claude/skills/sideband")).toList()*.fileName*.toString() == ["SKILL.md"]

        and: "the settings file is pretty JSON with exactly the hook entry"
        String settings = Files.readString(project.resolve(".claude/settings.json"))
        settings.contains('"UserPromptSubmit": [')
        settings.contains('"command": "\\"/opt/sideband/bin/sideband\\" hook prompt --agent claude"')
        settings.startsWith("{\n  \"hooks\": {")

        and: "the repository file is not given crossSessionInbound: a repository can only tighten it, and the report says where accept must go"
        report.inbound().name() == "claude-inbound"
        report.inbound().state() == "missing"
        report.inbound().path() == home.resolve(".claude/settings.json").toString()
        report.inbound().note().contains("accept in " + home.resolve(".claude/settings.json"))
        report.hook().note() == null
        !settings.contains("crossSessionInbound")

        and: "the Codex registration is the same command naming codex, so a hook shell without markers still knows its client"
        String codexHooks = Files.readString(project.resolve(".codex/hooks.json"))
        codexHooks.contains('"command": "\\"/opt/sideband/bin/sideband\\" hook prompt --agent codex"')
        !codexHooks.contains("crossSessionInbound")
    }

    void "reinstalling is idempotent and inspect agrees"() {
        given:
        installer.install(home, project)

        when:
        InstallReport again = installer.install(home, project)
        InstallReport inspected = installer.inspect(home, project)

        then:
        again.skills()*.state() == ["unchanged", "unchanged"]
        again.hook().state() == "unchanged"
        inspected.skills()*.state() == ["unchanged", "unchanged"]
        inspected.hook().state() == "installed"
        again.codexHook().state() == "unchanged"
        inspected.codexHook().state() == "installed"
        again.inbound().state() == "missing"
        inspected.inbound().state() == "missing"
    }

    void "inspect follows Claude Code's resolution: the user file decides, the project and local files can only tighten"() {
        given:
        Map<String, Path> files = [project: project.resolve(".claude/settings.json"),
                                   local  : project.resolve(".claude/settings.local.json"),
                                   user   : home.resolve(".claude/settings.json")]
        values.each { scope, inbound -> write(files[scope], inbound) }

        when:
        InstallReport.Item item = installer.inspect(home, project).inbound()

        then:
        item.state() == state
        item.path() == files[decidedBy].toString()
        item.note().contains("can only tighten it")
        item.note().contains("managed settings and --settings, which are not inspected")

        where:
        values                                              | state       | decidedBy
        [user: "accept"]                                    | "installed" | "user"
        [user: "accept", project: "hold"]                   | "held"      | "project"
        [user: "accept", local: "refuse"]                   | "refused"   | "local"
        [user: "hold", project: "accept", local: "accept"]  | "held"      | "user"
        [project: "accept"]                                 | "missing"   | "user"
        [local: "hold"]                                     | "held"      | "local"
        [:]                                                 | "missing"   | "user"
        [user: "accept", local: "maybe"]                    | "unknown"   | "local"
    }

    private static void write(Path file, String inbound) {
        Files.createDirectories(file.parent)
        Files.writeString(file, '{"crossSessionInbound": "' + inbound + '"}')
    }

    void "an unreadable settings file is reported as such for the inbound verdict"() {
        given:
        Files.createDirectories(home.resolve(".claude"))
        Files.writeString(home.resolve(".claude/settings.json"), "not json")

        expect:
        with(installer.inspect(home, project).inbound()) {
            state() == "unreadable"
            path() == home.resolve(".claude/settings.json").toString()
        }
    }

    void "invalid Codex hook configuration is not overwritten"() {
        given:
        Path hooksFile = project.resolve(".codex/hooks.json")
        Files.createDirectories(hooksFile.parent)
        Files.writeString(hooksFile, "not JSON")

        when:
        installer.install(home, project)

        then:
        thrown(UncheckedIOException)
        Files.readString(hooksFile) == "not JSON"
    }

    void "reinstall keeps the client's agent and updates an old executable path"() {
        given:
        Path hooksFile = project.resolve(".codex/hooks.json")
        Files.createDirectories(hooksFile.parent)
        Files.writeString(hooksFile, '''{"hooks":{"UserPromptSubmit":[{"hooks":[{"type":"command","command":"/old/sideband hook prompt --agent codex"}]}]}}''')

        when:
        installer.install(home, project)
        String first = Files.readString(hooksFile)
        InstallReport again = installer.install(home, project)

        then:
        Files.readString(hooksFile) == first
        first.count("hook prompt") == 1
        first.contains("hook prompt --agent codex")
        !first.contains("/old/")
        again.codexHook().state() == "unchanged"
        installer.inspect(home, project).codexHook().state() == "installed"
    }

    void "inspect on a clean machine reports everything missing"() {
        expect:
        installer.inspect(home, project).skills()*.state() == ["missing", "missing"]
        installer.inspect(home, project).hook().state() == "missing"
        installer.inspect(home, project).codexHook().state() == "missing"
        installer.inspect(home, project).inbound().state() == "missing"
    }

    void "a development symlink is replaced by a real copy and an edited copy is refreshed"() {
        given:
        Files.createDirectories(home.resolve(".claude/skills"))
        Files.createSymbolicLink(home.resolve(".claude/skills/sideband"), Path.of("skills/claude").toAbsolutePath())

        expect:
        installer.inspect(home, project).skills()[0].state() == "stale"

        when:
        InstallReport report = installer.install(home, project)

        then:
        report.skills()[0].state() == "updated"
        !Files.isSymbolicLink(home.resolve(".claude/skills/sideband"))

        when:
        Files.writeString(home.resolve(".claude/skills/sideband/SKILL.md"), "edited")

        then:
        installer.inspect(home, project).skills()[0].state() == "stale"
        installer.install(home, project).skills()[0].state() == "updated"
    }

    void "files an earlier version installed are removed on update"() {
        given:
        installer.install(home, project)
        Files.createDirectories(home.resolve(".claude/skills/sideband/hooks"))
        Files.writeString(home.resolve(".claude/skills/sideband/hooks/prompt.sh"), "#!/bin/sh")

        expect:
        installer.inspect(home, project).skills()[0].state() == "stale"

        when:
        InstallReport report = installer.install(home, project)

        then:
        report.skills()[0].state() == "updated"
        Files.list(home.resolve(".claude/skills/sideband")).toList()*.fileName*.toString() == ["SKILL.md"]
    }

    void "a file where the skill directory should be is a conflict and is left alone"() {
        given:
        Files.createDirectories(home.resolve(".agents/skills"))
        Files.writeString(home.resolve(".agents/skills/sideband"), "not a directory")

        when:
        InstallReport report = installer.install(home, project)

        then:
        report.skills()[1].state() == "conflict"
        Files.readString(home.resolve(".agents/skills/sideband")) == "not a directory"
    }

    void "existing settings and unrelated hooks are preserved, and an old sideband hook path is updated"() {
        given:
        Files.createDirectories(project.resolve(".claude"))
        Files.writeString(project.resolve(".claude/settings.json"), '''{
  "permissions": {"allow": ["Bash(ls:*)"]},
  "hooks": {
    "PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "echo pre"}]}],
    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "\\"$CLAUDE_PROJECT_DIR\\"/skills/claude/hooks/prompt.sh"}]}]
  }
}''')

        when:
        InstallReport report = installer.install(home, project)
        String settings = Files.readString(project.resolve(".claude/settings.json"))

        then:
        report.hook().state() == "updated"
        settings.contains('"allow": [\n      "Bash(ls:*)"')
        settings.contains('"command": "echo pre"')
        settings.contains('"command": "\\"/opt/sideband/bin/sideband\\" hook prompt --agent claude"')
        !settings.contains("CLAUDE_PROJECT_DIR")
        settings.count("hook prompt") == 1
    }

    void "a settings file that is not JSON is refused rather than clobbered"() {
        given:
        Files.createDirectories(project.resolve(".claude"))
        Files.writeString(project.resolve(".claude/settings.json"), "{ this is not json")

        when:
        installer.install(home, project)

        then:
        thrown(UncheckedIOException)
        Files.readString(project.resolve(".claude/settings.json")) == "{ this is not json"
    }
}
