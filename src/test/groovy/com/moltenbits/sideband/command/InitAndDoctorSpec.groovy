package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

class InitAndDoctorSpec extends CommandSpec {

    Path repo = TempRepo.init()

    Map runJson(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    void "init creates the state directory and configuration, and capture-human then needs no --human"() {
        given:
        TempRepo.git(repo, "config", "user.name", "James Hardwick")

        when:
        Map init = runJson("init", "--repo", repo.toString())

        then:
        init.state_directory == repo.toRealPath().resolve(".git/sideband").toString()
        init.config.human == [id: "james-hardwick", display_name: "James Hardwick"]

        when:
        Map captured = runJson("capture-human", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        captured.metadata.from == "human:james-hardwick"
        Files.readString(repo.resolve(".git/sideband/journal.md")).contains("## James-hardwick → Claude (via Claude)")
    }

    void "capture-human without init or --human is invalid input with a pointer to init"() {
        when:
        int code = run("capture-human", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("sideband init")
    }

    void "doctor reports an uninitialized repository without failing"() {
        when:
        Map report = runJson("doctor", "--repo", repo.toString())

        then:
        report.initialized == false
        report.protocol == "v1"
        report.config == null
        report.journal == null
        report.roles == [:]
        report.skills*.client == ["claude", "codex"]
    }

    void "doctor reports configuration, journal health, sessions, pending counts, and the lock owner"() {
        given:
        runJson("init", "--repo", repo.toString(), "--human", "james")
        runJson("capture-human", "--repo", repo.toString(), "--via", "claude", "--body-file", Files.writeString(repo.resolve("p.md"), "@codex hi").toString())
        runJson("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1", "--parent-pid", ProcessHandle.current().pid().toString())
        Files.writeString(repo.resolve(".git/sideband/journal.lock"), "12345")

        when:
        Map report = runJson("doctor", "--repo", repo.toString())

        then:
        report.initialized
        report.permissions == "rwx------"
        report.config.human.id == "james"
        report.journal.entries == 1
        report.journal.diagnostics == 0
        report.journal.incomplete_tail == false
        report.roles.codex.session_id == "s1"
        report.roles.codex.session_live == true
        report.roles.codex.backlog == 1
        report.roles.claude.session_id == null
        report.roles.claude.backlog == 0
        report.lock_owner_pid == "12345"
        !stdout.toString().contains("@codex hi")
    }
}
