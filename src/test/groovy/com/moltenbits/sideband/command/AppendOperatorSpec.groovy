package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal

import java.nio.file.Files
import java.nio.file.Path

class AppendOperatorSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

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
            metadata.via == "claude"
            metadata.to == ["claude"]
            metadata.type == "request"
            metadata.route == "direct"
            metadata.expects_reply == true
            metadata.delivery == [live: "auto", backlog: "confirm"]
            metadata.body_bytes == 12
            body == "fix the typo"
            start == 0
            pushes == []
        }
        Files.readString(journalFile).contains("\n## Operator → Claude (via Claude)\n\nfix the typo\n<!-- /sideband -->\n")
    }

    void "a directive routes to the named client and the body keeps the directive"() {
        when:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude",
                "--body-file", body("@codex review the locking behavior.").toString())

        then:
        json().metadata.to == ["codex"]
        json().body == "@codex review the locking behavior."
        json().pushes == [[role: "codex", outcome: "no-session", detail: null]]
    }

    void "@all is one broadcast entry naming both clients and the originating client"() {
        when:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "codex",
                "--body-file", body("@all review this").toString())

        then:
        json().metadata.to == ["claude", "codex"]
        json().metadata.route == "broadcast"
        json().metadata.via == "codex"
        context.getBean(Journal).readCompleteFrom(journalFile, 0).entries().size() == 1
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
        expect:
        run("append", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
        stderr.toString().contains("--type is required")
        run("append", "--repo", repo.toString(), "--from", "gemini", "--type", "status", "--to", "codex", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
    }

    void "the via option is case-insensitive and validated"() {
        expect:
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "Claude", "--body-file", body("hi").toString()) == ExitCode.OK
        run("append", "--from", "operator", "--repo", repo.toString(), "--via", "gemini", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
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

    void "a directory outside a repository gets a .sideband directory of its own"() {
        given:
        Path plain = TempRepo.plainDirectory()
        Path text = Files.writeString(plain.resolve("body.md"), "hello")

        expect:
        run("append", "--from", "operator", "--repo", plain.toString(), "--via", "claude", "--body-file", text.toString()) == ExitCode.OK
        Files.exists(plain.resolve(".sideband/journal.md"))
    }
}
