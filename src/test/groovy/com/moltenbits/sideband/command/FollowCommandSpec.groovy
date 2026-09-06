package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import io.micronaut.serde.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class FollowCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

    def setup() {
        Files.createDirectories(journalFile.parent)
    }

    List<Map> lines() {
        stdout.toString().readLines().findAll { it.trim() }.collect { context.getBean(ObjectMapper).readValue(it, Map) }
    }

    void "streams one wake line per batch as entries for the role arrive, and skips everything else"() {
        given:
        def following = CompletableFuture.supplyAsync {
            run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "0", "--max-batches", "2")
        }
        Journal journal = context.getBean(Journal)

        when:
        Thread.sleep(300)
        journal.append(journalFile, Fixtures.humanDraft("@codex not for claude", [Fixtures.CODEX]))
        journal.append(journalFile, Fixtures.humanDraft("@claude first", [Fixtures.CLAUDE], com.moltenbits.sideband.protocol.Role.CODEX))
        Thread.sleep(400)
        journal.append(journalFile, Fixtures.humanDraft("@claude second", [Fixtures.CLAUDE], com.moltenbits.sideband.protocol.Role.CODEX))
        int code = following.get(15, TimeUnit.SECONDS)

        then:
        code == ExitCode.OK
        List<Map> batches = lines()
        batches.size() == 2
        batches[0].entries == 1
        batches[0].actionable == 1
        batches[0].from == ["operator"]
        batches[0].diagnostics == 0
        batches[1].entries == 1
        batches[1].start == batches[0].end
        batches[1].end == Files.size(journalFile)
        batches.every { it.keySet().first() == "intent" }
        batches.every { it.intent == "Sideband delivery; use the Sideband skill (/sideband) for handling instructions" }

        and: "a wake line fits inside a host notification, which Claude Code caps at 500 characters"
        stdout.toString().readLines().findAll { it.trim() }.every { it.length() < 400 }
    }

    void "--once prints one wake line for what is already there and exits"() {
        given:
        Journal journal = context.getBean(Journal)
        journal.append(journalFile, Fixtures.humanDraft("@claude ready", [Fixtures.CLAUDE], com.moltenbits.sideband.protocol.Role.CODEX))
        journal.append(journalFile, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: com.moltenbits.sideband.protocol.MessageType.ACK,
                replyTo: "019a", causedBy: null, expectsReply: false, body: "received"))

        when:
        int code = run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "0", "--once")

        then: "the ack is not counted, but the scan moved past it"
        code == ExitCode.OK
        lines().size() == 1
        lines()[0].entries == 1
        lines()[0].end == Files.size(journalFile)
    }

    void "--once with --timeout gives up with the timed-out code and still reports how far it scanned"() {
        given:
        context.getBean(Journal).append(journalFile, Fixtures.humanDraft("@codex not for claude", [Fixtures.CODEX]))

        when:
        int code = run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "0", "--once", "--timeout", "0")

        then:
        code == ExitCode.TIMED_OUT
        lines()[0].entries == 0
        lines()[0].end == Files.size(journalFile)
    }

    void "--all counts every entry regardless of recipient, acks included"() {
        given:
        Journal journal = context.getBean(Journal)
        journal.append(journalFile, Fixtures.humanDraft("@codex one", [Fixtures.CODEX]))
        journal.append(journalFile, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: com.moltenbits.sideband.protocol.MessageType.ACK,
                replyTo: "019a", causedBy: null, expectsReply: false, body: "received"))

        when:
        int code = run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "0", "--once", "--all")

        then:
        code == ExitCode.OK
        lines()[0].entries == 2
    }

    void "--timeout without --once, and a negative timeout, are invalid input"() {
        expect:
        run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "0", "--timeout", "5", "--max-batches", "1") == ExitCode.INVALID_INPUT
        run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "0", "--once", "--timeout", "-1") == ExitCode.INVALID_INPUT
    }

    void "a directory outside a repository follows its own .sideband journal"() {
        expect:
        run("follow", "--repo", TempRepo.plainDirectory().toString(), "--role", "claude", "--from", "0", "--once", "--timeout", "0") == ExitCode.TIMED_OUT
    }

    void "the offset must not be negative"() {
        expect:
        run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "-1", "--max-batches", "1") == ExitCode.INVALID_INPUT
    }
}
