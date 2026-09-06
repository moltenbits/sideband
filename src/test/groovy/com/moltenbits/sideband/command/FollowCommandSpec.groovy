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
        batches.every { it.keySet().first() == "handling" }
        batches.every { it.handling.startsWith("Sideband: new journal entries for Claude") }
        batches.every { it.handling.contains("Run `sideband pending`") }

        and: "a wake line fits inside a host notification, which Claude Code caps at 500 characters"
        stdout.toString().readLines().findAll { it.trim() }.every { it.length() < 400 }
    }

    void "the offset must not be negative"() {
        expect:
        run("follow", "--repo", repo.toString(), "--role", "claude", "--from", "-1", "--max-batches", "1") == ExitCode.INVALID_INPUT
    }
}
