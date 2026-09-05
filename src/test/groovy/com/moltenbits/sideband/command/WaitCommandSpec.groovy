package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class WaitCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

    def setup() {
        Files.createDirectories(journalFile.parent)
    }

    Entry append(def draft) {
        context.getBean(Journal).append(journalFile, draft)
    }

    void "returns entries already present past the offset with their effective policy"() {
        given:
        append(Fixtures.humanDraft("ready"))

        when:
        int code = run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "5")

        then:
        code == ExitCode.OK
        long size = Files.size(journalFile)
        with(json()) {
            start == 0
            end == size
            entries.size() == 1
            entries[0].body == "ready"
            entries[0].metadata.from == "human:james"
            entries[0].effective_live == "auto"
            entries[0].lineage_problem == null
            diagnostics == []
            timed_out == false
        }
    }

    void "blocks until an entry is appended by another process"() {
        given:
        def waiting = CompletableFuture.supplyAsync { run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "10") }

        when:
        Thread.sleep(300)
        append(Fixtures.humanDraft("late"))
        int code = waiting.get(15, TimeUnit.SECONDS)

        then:
        code == ExitCode.OK
        json().entries*.body == ["late"]
    }

    void "gives up with the timeout exit code and an empty result"() {
        when:
        int code = run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "0")

        then:
        code == ExitCode.TIMED_OUT
        json() == [start: 0, end: 0, entries: [], diagnostics: [], timed_out: true]
    }

    void "with a role, only open entries for that role are returned and the end offset still advances"() {
        given:
        Entry toCodex = append(Fixtures.humanDraft("@codex one", [Fixtures.CODEX]))
        Entry toClaude = append(Fixtures.humanDraft("@claude two", [Fixtures.CLAUDE]))
        Entry fromCodex = append(Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.STATUS,
                causedBy: null, expectsReply: false, body: "status from codex"))
        run("resolve", "--repo", repo.toString(), "--role", "codex", "--as", "dismissed", toCodex.metadata().id())
        stdout = new StringWriter()
        Entry stillOpen = append(Fixtures.humanDraft("@codex three", [Fixtures.CODEX]))

        when:
        int code = run("wait", "--repo", repo.toString(), "--role", "codex", "--from", "0", "--timeout", "5")

        then:
        code == ExitCode.OK
        json().entries*.metadata*.id == [stillOpen.metadata().id()]
        json().end == Files.size(journalFile)
    }

    void "with a role and nothing open, the timeout result still reports how far it scanned"() {
        given:
        append(Fixtures.humanDraft("@claude only", [Fixtures.CLAUDE]))

        when:
        int code = run("wait", "--repo", repo.toString(), "--role", "codex", "--from", "0", "--timeout", "0")

        then:
        code == ExitCode.TIMED_OUT
        json().entries == []
        json().end == Files.size(journalFile)
    }

    void "an actionable agent entry with unverifiable lineage is delivered under confirm with the problem stated"() {
        given:
        Entry h = append(Fixtures.humanDraft("@claude go", [Fixtures.CLAUDE]))
        // written directly through the journal, bypassing append-agent's refusal
        append(Fixtures.agentDraft(from: Fixtures.CLAUDE, to: [Fixtures.CODEX], causedBy: "ghost"))

        when:
        run("wait", "--repo", repo.toString(), "--role", "codex", "--from", "0", "--timeout", "5")

        then:
        json().entries.size() == 1
        json().entries[0].effective_live == "confirm"
        json().entries[0].lineage_problem.contains("missing ancestor")
    }

    void "diagnostics for skipped regions are reported alongside entries"() {
        given:
        Files.writeString(journalFile, "old spike bytes\n<!-- /sideband -->\n")
        append(Fixtures.humanDraft("real"))

        when:
        int code = run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "5")

        then:
        code == ExitCode.OK
        json().entries*.body == ["real"]
        json().diagnostics*.reason == ["unframed bytes before the next entry"]
    }

    void "the offset is required"() {
        expect:
        run("wait", "--repo", repo.toString()) == ExitCode.INVALID_INPUT
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
