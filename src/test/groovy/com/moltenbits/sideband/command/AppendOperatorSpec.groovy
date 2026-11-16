package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role

import java.nio.file.Files
import java.nio.file.Path

class AppendOperatorSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path stateDir = home.resolve(".sideband")
    Path journalFile = stateDir.resolve("journal.db")

    Path body(String text) {
        Files.writeString(repo.resolve("body.md"), text)
    }

    void "an undirected prompt is journaled for the client it was typed into"() {
        when:
        int code = run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude",
                "--body-file", body("fix the typo").toString())

        then:
        code == ExitCode.OK
        with(json()) {
            metadata.from == "operator"
            metadata.via == "repo/claude"
            metadata.to == ["repo/claude"]
            metadata.type == "request"
            metadata.route == "direct"
            metadata.expects_reply == true
            metadata.delivery == [live: "auto", backlog: "confirm"]
            body == "fix the typo"
            seq == 1
            pushes == []
        }
        with(context.getBean(Journal).readAfter(stateDir, 0).entries()) {
            size() == 1
            it[0].body() == "fix the typo"
            it[0].metadata().from() == Fixtures.OPERATOR
            it[0].metadata().via() == Fixtures.CLAUDE
            it[0].metadata().to() == [Fixtures.CLAUDE]
        }
    }

    void "a directive routes to the named client and the body keeps the directive"() {
        when:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude",
                "--body-file", body("@codex review the locking behavior.").toString())

        then:
        json().metadata.to == ["repo/codex"]
        json().body == "@codex review the locking behavior."
        json().pushes == [[recipient: "repo/codex", outcome: "no-session", detail: null]]
    }

    void "@all is one broadcast entry naming everyone joined in the project, the originating client included"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "t1")
        stdout = new StringWriter()

        when:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "codex",
                "--body-file", body("@all review this").toString())

        then:
        json().metadata.to == ["repo/claude", "repo/codex"]
        json().metadata.route == "broadcast"
        json().metadata.via == "repo/codex"
        context.getBean(Journal).readAfter(stateDir, 0).entries().size() == 1
    }

    void "the originating client's own turn is never pushed to it"() {
        when:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "codex",
                "--body-file", body("just for codex").toString())

        then:
        json().pushes == []
    }

    void "the operator's entry takes only a body: recipients, links, and other types are refused"() {
        expect:
        run("append", "--repo", repo.toString(), "--from", "operator", "--via", "claude", "--to", "codex", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        run("append", "--repo", repo.toString(), "--from", "operator", "--via", "claude", "--type", "status", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        run("append", "--repo", repo.toString(), "--from", "operator", "--via", "claude", "--reply-to", "x", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        stderr.toString().contains("--from operator takes only the body")
        !Files.exists(journalFile)
    }

    void "an agent entry needs --type; an unknown --from is rejected"() {
        expect: "and --via cannot quietly replace an agent author"
        run("append", "--repo", repo.toString(), "--from", "claude", "--via", "codex", "--type", "status", "--to", "operator",
                "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        stderr.toString().contains("--via only applies with --from operator")
        !Files.exists(journalFile)
        run("append", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        stderr.toString().contains("--type is required")
        run("append", "--repo", repo.toString(), "--from", "Gemini!", "--type", "status", "--to", "codex", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
    }

    void "the via option names an agent by its address and is validated"() {
        expect:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "fable", "--body-file", body("hi").toString()) == ExitCode.OK
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "operator", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude:fable", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "Gemini!", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
    }

    void "words addressed to a project nobody registered are recorded for the client typed into, and the failed route is reported"() {
        when:
        int code = run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude",
                "--body-file", body("nowhere/@opus look at this").toString())

        then:
        code == ExitCode.OK
        json().metadata.to == ["repo/claude"]
        json().unrouted == "nowhere"
        json().pushes == []
        stderr.toString().contains("nowhere is not a Sideband project")
    }

    void "reads the body from standard input when no file is given"() {
        given:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("from stdin".bytes)

        when:
        int code = run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude")

        then:
        code == ExitCode.OK
        json().body == "from stdin"

        cleanup:
        System.in = original
    }

    void "an empty body is rejected before anything is written"() {
        when:
        int code = run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file", body("  \n").toString())

        then:
        code == ExitCode.INVALID_INPUT
        !Files.exists(journalFile)
    }

    void "a directory outside a repository is a project of its own, kept in the one journal"() {
        given:
        Path plain = Files.createDirectories(TempRepo.plainDirectory().resolve("notes"))
        Path text = Files.writeString(plain.resolve("body.md"), "hello")

        expect:
        run("append", "--from", "operator", "--repo", plain.toString(), "--via", "claude", "--body-file", text.toString()) == ExitCode.OK
        json().metadata.via == "notes/claude"
        Files.exists(journalFile)
        !Files.exists(plain.resolve(".sideband"))
    }
}
