package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal

import java.nio.file.Files
import java.nio.file.Path

class InitAndDoctorSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path home = Files.createTempDirectory("home")

    Map runJson(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    String text(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        stdout.toString()
    }

    void "init creates the state directory, installs both skills and the hook, and append --from operator journals the operator"() {
        when:
        int code = run("init", "--repo", repo.toString(), "--home", home.toString())

        then:
        code == ExitCode.OK
        Files.isDirectory(repo.resolve(".git/sideband"))
        Files.exists(home.resolve(".claude/skills/sideband/SKILL.md"))
        Files.exists(home.resolve(".agents/skills/sideband/SKILL.md"))
        Files.exists(repo.resolve(".claude/settings.json"))
        Files.exists(repo.resolve(".codex/hooks.json"))

        when:
        Map captured = runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        captured.metadata.from == "operator"
        context.getBean(Journal).readAfter(repo.resolve(".git/sideband"), 0).entries()*.body() == ["hello"]
    }

    void "init tells the user to accept pushes in the user settings, never in a repository file that only tightens them"() {
        given: "no user setting, and a repository file holding pushes"
        Files.createDirectories(repo.resolve(".claude"))
        Files.writeString(repo.resolve(".claude/settings.local.json"), '{"crossSessionInbound":"hold"}')

        when:
        int code = run("init", "--repo", repo.toString(), "--home", home.toString())
        String step = stdout.toString().readLines().find { it.contains("crossSessionInbound") }

        then:
        code == ExitCode.OK
        step.contains("accept in " + home.resolve(".claude/settings.json"))
        step.contains(repo.toRealPath().resolve(".claude/settings.local.json").toString())
    }

    void "append --from operator works without init because nothing about the operator is configured"() {
        when:
        int code = run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        code == ExitCode.OK
        json().metadata.from == "operator"
    }

    void "outside any git repository the state lives in a .sideband directory in the working directory, and the hooks land there too"() {
        given:
        Path plain = Files.createDirectories(TempRepo.plainDirectory().resolve("nested/work"))

        when:
        int initialised = run("init", "--repo", plain.toString(), "--home", home.toString())

        then:
        initialised == ExitCode.OK
        Files.isDirectory(plain.resolve(".sideband"))
        Files.exists(plain.resolve(".claude/settings.json"))
        Files.exists(plain.resolve(".codex/hooks.json"))
        !Files.exists(plain.getParent().resolve(".claude"))
        !Files.exists(plain.getParent().resolve(".codex"))

        and: "doctor inspects the same place"
        text("doctor", "--repo", plain.toString(), "--home", home.toString()).readLines().find { it.startsWith("  claude hook") }.contains("installed")

        when:
        stdout = new StringWriter()
        int code = run("append", "--from", "operator", "--repo", plain.toString(), "--via", "codex", "--body-file",
                Files.writeString(plain.resolve("p.md"), "no repo here").toString())

        then:
        code == ExitCode.OK
        context.getBean(Journal).readAfter(plain.resolve(".sideband"), 0).entries()*.body() == ["no repo here"]
    }

    void "doctor reports an uninitialized repository without failing"() {
        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", home.toString())

        then:
        report.readLines()[0] ==~ /sideband \S+ \(protocol v1\)/
        report.contains("Not initialized")
        !report.contains("Roles:")
        report.readLines().find { it.startsWith("  claude skill") }.contains("missing")
        report.readLines().find { it.startsWith("  claude hook") }.contains("missing")
        report.readLines().find { it.startsWith("  claude inbound") }.contains("missing")
        report.contains("can only tighten it")
    }

    void "doctor reports database health, sessions, and pending counts"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file", Files.writeString(repo.resolve("p.md"), "@codex hi").toString())
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")

        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", home.toString())
        List<String> lines = report.readLines()

        then:
        lines.find { it.startsWith("State directory: ") }.endsWith(repo.toRealPath().resolve(".git/sideband").toString() + " (rwx------)")
        lines.find { it.startsWith("Database: ") }.startsWith("Database: " + repo.toRealPath().resolve(".git/sideband/sideband.db").toString() + ", 1 entry, ")
        lines.find { it.startsWith("Database: ") }.endsWith("integrity ok")
        lines.find { it.startsWith("  codex") }.contains("session s1")
        lines.find { it.startsWith("  codex") }.contains("1 open")
        lines.find { it.startsWith("  claude") }.contains("not joined")
        !report.contains("@codex hi")
    }
}
