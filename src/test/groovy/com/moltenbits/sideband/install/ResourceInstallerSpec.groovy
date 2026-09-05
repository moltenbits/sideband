package com.moltenbits.sideband.install

import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class ResourceInstallerSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Installer installer = context.getBean(Installer)
    Path home = Files.createTempDirectory("home")
    Path project = Files.createTempDirectory("project")

    void "the component is exposed only through its interface"() {
        expect:
        installer instanceof ResourceInstaller
    }

    void "a fresh install writes both skills from the embedded copies and registers the hook"() {
        when:
        InstallReport report = installer.install(home, project)

        then:
        report.skills()*.state() == ["installed", "installed"]
        report.skills()*.name() == ["claude", "codex"]
        report.hook().state() == "added"
        Files.readString(home.resolve(".claude/skills/sideband/SKILL.md")).startsWith("---\nname: sideband")
        Files.readString(home.resolve(".agents/skills/sideband/SKILL.md")).contains("Codex adapter")
        Files.getPosixFilePermissions(home.resolve(".claude/skills/sideband/hooks/prompt.sh")).contains(PosixFilePermission.OWNER_EXECUTE)
        Files.readString(home.resolve(".claude/skills/sideband/SKILL.md")) == Files.readString(Path.of("skills/sideband-claude/SKILL.md"))

        and: "the settings file is pretty JSON with exactly the hook entry"
        String settings = Files.readString(project.resolve(".claude/settings.json"))
        settings.contains('"UserPromptSubmit": [')
        settings.contains('"command": "\\"$HOME\\"/.claude/skills/sideband/hooks/prompt.sh"')
        settings.startsWith("{\n  \"hooks\": {")
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
    }

    void "inspect on a clean machine reports everything missing"() {
        expect:
        installer.inspect(home, project).skills()*.state() == ["missing", "missing"]
        installer.inspect(home, project).hook().state() == "missing"
    }

    void "a development symlink is replaced by a real copy and an edited copy is refreshed"() {
        given:
        Files.createDirectories(home.resolve(".claude/skills"))
        Files.createSymbolicLink(home.resolve(".claude/skills/sideband"), Path.of("skills/sideband-claude").toAbsolutePath())

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
    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "\\"$CLAUDE_PROJECT_DIR\\"/skills/sideband-claude/hooks/prompt.sh"}]}]
  }
}''')

        when:
        InstallReport report = installer.install(home, project)
        String settings = Files.readString(project.resolve(".claude/settings.json"))

        then:
        report.hook().state() == "updated"
        settings.contains('"allow": [\n      "Bash(ls:*)"')
        settings.contains('"command": "echo pre"')
        settings.contains('"command": "\\"$HOME\\"/.claude/skills/sideband/hooks/prompt.sh"')
        !settings.contains("CLAUDE_PROJECT_DIR")
        settings.count("prompt.sh") == 1
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
