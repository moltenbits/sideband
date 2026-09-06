package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

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
        Map init = runJson("init", "--repo", repo.toString(), "--home", home.toString())

        then:
        init.state_directory == repo.toRealPath().resolve(".git/sideband").toString()
        init.clients.skills*.state == ["installed", "installed"]
        init.clients.hook.state == "added"
        init.clients.inbound.state == "added"
        Files.exists(home.resolve(".claude/skills/sideband/SKILL.md"))
        Files.exists(repo.resolve(".claude/settings.json"))

        when:
        Map captured = runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        captured.metadata.from == "operator"
        Files.readString(repo.resolve(".git/sideband/journal.md")).contains("## Operator → Claude (via Claude)")
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
        Map init = runJson("init", "--repo", plain.toString(), "--home", home.toString())

        then:
        init.state_directory == plain.toRealPath().resolve(".sideband").toString()
        Files.isDirectory(plain.resolve(".sideband"))
        init.clients.hook.state == "added"
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
        Files.readString(plain.resolve(".sideband/journal.md")).contains("no repo here")
    }

    void "doctor reports an uninitialized repository without failing"() {
        when:
        Map report = runJson("doctor", "--repo", repo.toString(), "--home", home.toString())

        then:
        report.initialized == false
        report.protocol == "v1"
        report.version ==~ /sideband \S+ \(protocol v1\)/
        report.journal == null
        report.roles == [:]
        report.clients.skills*.state == ["missing", "missing"]
        report.clients.hook.state == "missing"
        report.clients.inbound.state == "missing"
        report.clients.inbound.note.contains("not inspected")
    }

    void "doctor reports journal health, sessions, pending counts, and the lock owner"() {
        given:
        runJson("init", "--repo", repo.toString(), "--skip-clients")
        runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file", Files.writeString(repo.resolve("p.md"), "@codex hi").toString())
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        Files.writeString(repo.resolve(".git/sideband/journal.lock"), "12345")

        when:
        Map report = runJson("doctor", "--repo", repo.toString(), "--home", home.toString())

        then:
        report.initialized
        report.permissions == "rwx------"
        report.journal.entries == 1
        report.journal.diagnostics == 0
        report.journal.incomplete_tail == false
        report.roles.codex.session_id == "s1"
        report.roles.codex.open == 1
        report.roles.claude.session_id == null
        report.roles.claude.open == 0
        report.lock_owner_pid == "12345"
        !stdout.toString().contains("@codex hi")
    }
}
