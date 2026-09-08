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
        runJson("doctor", "--repo", plain.toString(), "--home", home.toString()).clients.hook.state != "missing"

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
        Map report = runJson("doctor", "--repo", repo.toString(), "--home", home.toString())

        then:
        report.initialized == false
        report.protocol == "v1"
        report.version ==~ /sideband \S+ \(protocol v1\)/
        report.database == null
        report.roles == [:]
        report.clients.skills*.state == ["missing", "missing"]
        report.clients.hook.state == "missing"
        report.clients.inbound.state == "missing"
        report.clients.inbound.note.contains("can only tighten it")
    }

    void "doctor reports database health, sessions, and pending counts"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file", Files.writeString(repo.resolve("p.md"), "@codex hi").toString())
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")

        when:
        Map report = runJson("doctor", "--repo", repo.toString(), "--home", home.toString())

        then:
        report.initialized
        report.permissions == "rwx------"
        report.database.path == repo.toRealPath().resolve(".git/sideband/sideband.db").toString()
        report.database.bytes > 0
        report.database.entries == 1
        report.database.integrity == "ok"
        report.roles.codex.session_id == "s1"
        report.roles.codex.open == 1
        report.roles.claude.session_id == null
        report.roles.claude.open == 0
        !report.containsKey("lock_owner_pid")
        !stdout.toString().contains("@codex hi")
    }
}
