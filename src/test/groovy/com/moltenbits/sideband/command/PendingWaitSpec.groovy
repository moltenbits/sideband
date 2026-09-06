package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.Role
import io.micronaut.serde.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** pending --wait and --stream: the listener is the same command as the report. */
class PendingWaitSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

    def setup() {
        Files.createDirectories(journalFile.parent)
    }

    List<Map> lines() {
        stdout.toString().readLines().findAll { it.trim() }.collect { context.getBean(ObjectMapper).readValue(it, Map) }
    }

    void "--stream prints one report per batch as entries for the role arrive, skips everything else, and never advances the read position"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()
        def streaming = CompletableFuture.supplyAsync {
            run("pending", "--repo", repo.toString(), "--role", "claude", "--wait", "--stream", "--max-batches", "2")
        }
        Journal journal = context.getBean(Journal)

        when:
        Thread.sleep(300)
        journal.append(journalFile, Fixtures.humanDraft("@codex not for claude", [Fixtures.CODEX]))
        journal.append(journalFile, Fixtures.humanDraft("@claude first", [Fixtures.CLAUDE], Role.CODEX))
        Thread.sleep(400)
        journal.append(journalFile, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.STATUS,
                causedBy: null, expectsReply: false, body: "second"))
        int code = streaming.get(15, TimeUnit.SECONDS)

        then:
        code == ExitCode.OK
        List<Map> reports = lines()
        reports.size() == 2
        reports.every { it.keySet().first() == "intent" }
        reports.every { it.intent == "Sideband delivery; use the Sideband skill (/sideband) for handling instructions" }
        reports[0].open*.entry*.body == ["@claude first"]
        reports[0].updates == []
        reports[1].open*.entry*.body == ["@claude first"]
        reports[1].updates*.body == ["second"]

        when: "the read position did not move, so a plain pending still shows the update once"
        stdout = new StringWriter()
        run("pending", "--repo", repo.toString(), "--role", "claude")
        List first = json().updates*.body
        stdout = new StringWriter()
        run("pending", "--repo", repo.toString(), "--role", "claude")
        List second = json().updates

        then:
        first == ["second"]
        second == []
    }

    void "--wait returns at once with the report when something is already there, and advances"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        Journal journal = context.getBean(Journal)
        journal.append(journalFile, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.STATUS,
                causedBy: null, expectsReply: false, body: "already here"))
        stdout = new StringWriter()

        expect:
        run("pending", "--repo", repo.toString(), "--role", "claude", "--wait", "--timeout", "5") == ExitCode.OK
        json().updates*.body == ["already here"]
        json().end == Files.size(journalFile)

        when: "shown once"
        stdout = new StringWriter()
        run("pending", "--repo", repo.toString(), "--role", "claude")

        then:
        json().updates == []
    }

    void "--wait ignores acks and entries for the other role"() {
        given:
        Journal journal = context.getBean(Journal)
        journal.append(journalFile, Fixtures.humanDraft("@codex one", [Fixtures.CODEX]))
        journal.append(journalFile, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.ACK,
                replyTo: "019a", causedBy: null, expectsReply: false, body: "received"))

        expect:
        run("pending", "--repo", repo.toString(), "--role", "claude", "--wait", "--timeout", "0") == ExitCode.TIMED_OUT
        json().end == Files.size(journalFile)
    }

    void "--wait with --timeout gives up with the timed-out code, still printing the report, without advancing"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        run("pending", "--repo", repo.toString(), "--role", "claude", "--wait", "--timeout", "0") == ExitCode.TIMED_OUT
        json().intent.startsWith("Sideband delivery")
        json().open == []
    }

    void "--timeout and --stream without --wait, and a negative timeout, are invalid input"() {
        expect:
        run("pending", "--repo", repo.toString(), "--role", "claude", "--timeout", "5") == ExitCode.INVALID_INPUT
        run("pending", "--repo", repo.toString(), "--role", "claude", "--stream") == ExitCode.INVALID_INPUT
        run("pending", "--repo", repo.toString(), "--role", "claude", "--wait", "--timeout", "-1") == ExitCode.INVALID_INPUT
    }

    void "a directory outside a repository waits on its own .sideband journal"() {
        expect:
        run("pending", "--repo", TempRepo.plainDirectory().toString(), "--role", "claude", "--wait", "--timeout", "0") == ExitCode.TIMED_OUT
    }
}
