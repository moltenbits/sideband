package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType

import java.nio.file.Files
import java.nio.file.Path

/** `sideband log` is the readable form of the discussion: Markdown on stdout, nothing else. */
class LogCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path state = repo.resolve(".git/sideband")

    def setup() {
        Files.createDirectories(state)
    }

    void "renders every entry as a heading, its metadata, and the verbatim body, oldest first"() {
        given:
        Journal journal = context.getBean(Journal)
        def first = journal.append(state, Fixtures.humanDraft("@codex look at this\n\n## not a heading of ours\n<!-- /sideband -->", [Fixtures.CODEX]))
        def reply = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE, Fixtures.OPERATOR],
                type: MessageType.REPLY, replyTo: first.metadata().id(), causedBy: null, expectsReply: false, body: "done"))

        when:
        int code = run("log", "--repo", repo.toString())
        String text = stdout.toString()

        then:
        code == ExitCode.OK
        stderr.toString().isEmpty()
        text == """## Operator → Codex (via Claude)

- position: 1
- id: ${first.metadata().id()}
- created: ${first.metadata().createdAt()}
- type: request (expects a reply)
- to: codex

@codex look at this

## not a heading of ours
<!-- /sideband -->

---

## Codex → Claude + Operator

- position: 2
- id: ${reply.metadata().id()}
- created: ${reply.metadata().createdAt()}
- type: reply
- to: claude, operator
- reply_to: ${first.metadata().id()}

done

---

"""
    }

    void "--after and --limit select a range of positions"() {
        given:
        Journal journal = context.getBean(Journal)
        (1..4).each { journal.append(state, Fixtures.humanDraft("entry $it", [Fixtures.CODEX])) }

        expect:
        run("log", "--repo", repo.toString(), "--after", "2") == ExitCode.OK
        stdout.toString().findAll(/- position: (\d+)/) { it[1] } == ["3", "4"]

        when:
        stdout = new StringWriter()

        then:
        run("log", "--repo", repo.toString(), "--after", "1", "--limit", "2") == ExitCode.OK
        stdout.toString().findAll(/- position: (\d+)/) { it[1] } == ["2", "3"]
        stdout.toString().contains("entry 2") && stdout.toString().contains("entry 3")
        !stdout.toString().contains("entry 4")
    }

    void "a repository without a discussion prints nothing and succeeds"() {
        expect:
        run("log", "--repo", TempRepo.init().toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(TempRepo.init().resolve(".git/sideband/sideband.db"))
    }

    void "negative ranges are invalid input"() {
        expect:
        run("log", "--repo", repo.toString(), "--after", "-1") == ExitCode.INVALID_INPUT
        run("log", "--repo", repo.toString(), "--limit", "-1") == ExitCode.INVALID_INPUT
    }
}
