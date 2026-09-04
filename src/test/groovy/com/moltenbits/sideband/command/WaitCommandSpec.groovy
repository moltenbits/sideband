package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class WaitCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

    void "returns entries already present past the offset"() {
        given:
        Files.createDirectories(journalFile.parent)
        context.getBean(Journal).append(journalFile, "ready")

        when:
        int code = run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "5")

        then:
        code == ExitCode.OK
        json() == [start: 0, end: Files.size(journalFile), entries: ["ready"], timed_out: false]
    }

    void "blocks until an entry is appended by another process"() {
        given:
        def waiting = CompletableFuture.supplyAsync { run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "10") }

        when:
        Thread.sleep(300)
        Files.createDirectories(journalFile.parent)
        context.getBean(Journal).append(journalFile, "late")
        int code = waiting.get(15, TimeUnit.SECONDS)

        then:
        code == ExitCode.OK
        json().entries == ["late"]
        json().timed_out == false
    }

    void "gives up with the timeout exit code and an empty result"() {
        when:
        int code = run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "0")

        then:
        code == ExitCode.TIMED_OUT
        json() == [start: 0, end: 0, entries: [], timed_out: true]
    }

    void "the offset is required"() {
        when:
        int code = run("wait", "--repo", repo.toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("--from")
    }

    void "negative offsets and timeouts are invalid input"() {
        expect:
        run("wait", "--repo", repo.toString(), "--from", "-1") == ExitCode.INVALID_INPUT
        run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "-1") == ExitCode.INVALID_INPUT
    }

    void "a directory outside a repository fails with the repository exit code"() {
        expect:
        run("wait", "--repo", TempRepo.plainDirectory().toString(), "--from", "0", "--timeout", "1") == ExitCode.NOT_A_REPOSITORY
    }
}
