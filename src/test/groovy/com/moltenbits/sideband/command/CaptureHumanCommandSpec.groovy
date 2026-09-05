package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal

import java.nio.file.Files
import java.nio.file.Path

class CaptureHumanCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

    Path body(String text) {
        Files.writeString(repo.resolve("body.md"), text)
    }

    void "an undirected prompt is journaled for the client it was typed into"() {
        when:
        int code = run("capture-human", "--repo", repo.toString(), "--via", "claude", "--human", "james",
                "--body-file", body("fix the typo").toString())

        then:
        code == ExitCode.OK
        with(json()) {
            metadata.from == "human:james"
            metadata.via == "claude"
            metadata.to == ["claude"]
            metadata.type == "instruction"
            metadata.route == "direct"
            metadata.expects_reply == true
            metadata.delivery == [live: "auto", backlog: "confirm"]
            metadata.body_bytes == 12
            body == "fix the typo"
            start == 0
        }
        Files.readString(journalFile).contains("\n## James → Claude (via Claude)\n\nfix the typo\n<!-- /sideband -->\n")
    }

    void "a directive routes to the named client and the body keeps the directive"() {
        when:
        run("capture-human", "--repo", repo.toString(), "--via", "claude", "--human", "james",
                "--body-file", body("@codex review the locking behavior.").toString())

        then:
        json().metadata.to == ["codex"]
        json().body == "@codex review the locking behavior."
    }

    void "@all is one broadcast entry naming both clients and the originating client"() {
        when:
        run("capture-human", "--repo", repo.toString(), "--via", "codex", "--human", "james",
                "--body-file", body("@all review this").toString())

        then:
        json().metadata.to == ["claude", "codex"]
        json().metadata.route == "broadcast"
        json().metadata.via == "codex"
        context.getBean(Journal).readCompleteFrom(journalFile, 0).entries().size() == 1
    }

    void "the via option is case-insensitive and validated"() {
        expect:
        run("capture-human", "--repo", repo.toString(), "--via", "Claude", "--human", "james", "--body-file", body("hi").toString()) == ExitCode.OK
        run("capture-human", "--repo", repo.toString(), "--via", "gemini", "--human", "james", "--body-file", body("hi").toString()) == ExitCode.INVALID_INPUT
    }

    void "an invalid human identifier is invalid input"() {
        when:
        int code = run("capture-human", "--repo", repo.toString(), "--via", "claude", "--human", "James Hardwick", "--body-file", body("hi").toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("not a participant")
    }

    void "reads the body from standard input when no file is given"() {
        given:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("from stdin".bytes)

        when:
        int code = run("capture-human", "--repo", repo.toString(), "--via", "claude", "--human", "james")

        then:
        code == ExitCode.OK
        json().body == "from stdin"

        cleanup:
        System.in = original
    }

    void "an empty body is rejected before anything is written"() {
        when:
        int code = run("capture-human", "--repo", repo.toString(), "--via", "claude", "--human", "james", "--body-file", body("  \n").toString())

        then:
        code == ExitCode.INVALID_INPUT
        !Files.exists(journalFile)
    }

    void "a directory outside a repository fails with the repository exit code"() {
        given:
        Path plain = TempRepo.plainDirectory()
        Path text = Files.writeString(plain.resolve("body.md"), "hello")

        expect:
        run("capture-human", "--repo", plain.toString(), "--via", "claude", "--human", "james", "--body-file", text.toString()) == ExitCode.NOT_A_REPOSITORY
    }
}
