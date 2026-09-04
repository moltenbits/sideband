package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

class AppendCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()

    void "appends a body file to the repository journal and prints its byte range"() {
        given:
        Path body = Files.writeString(repo.resolve("body.md"), "@codex review the locking behavior.")

        when:
        int code = run("append", "--repo", repo.toString(), "--body-file", body.toString())

        then:
        code == ExitCode.OK
        json() == [start: 0, end: Files.size(repo.resolve(".git/sideband/journal.md"))]
        Files.readString(repo.resolve(".git/sideband/journal.md")) == "@codex review the locking behavior.\n<!-- /sideband -->\n"
    }

    void "a second append starts where the first ended"() {
        given:
        Path body = Files.writeString(repo.resolve("body.md"), "one")
        run("append", "--repo", repo.toString(), "--body-file", body.toString())
        long firstEnd = json().end
        stdout = new StringWriter()

        when:
        run("append", "--repo", repo.toString(), "--body-file", body.toString())

        then:
        json().start == firstEnd
    }

    void "reads the body from standard input when no file is given"() {
        given:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("from stdin".bytes)

        when:
        int code = run("append", "--repo", repo.toString())

        then:
        code == ExitCode.OK
        Files.readString(repo.resolve(".git/sideband/journal.md")).startsWith("from stdin\n")

        cleanup:
        System.in = original
    }

    void "an empty body is rejected as invalid input"() {
        given:
        Path body = Files.writeString(repo.resolve("body.md"), "   \n")

        when:
        int code = run("append", "--repo", repo.toString(), "--body-file", body.toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("empty")
        !Files.exists(repo.resolve(".git/sideband/journal.md"))
    }

    void "a directory outside a repository fails with the repository exit code"() {
        given:
        Path plain = TempRepo.plainDirectory()
        Path body = Files.writeString(plain.resolve("body.md"), "hello")

        when:
        int code = run("append", "--repo", plain.toString(), "--body-file", body.toString())

        then:
        code == ExitCode.NOT_A_REPOSITORY
        stderr.toString().contains("not inside a Git repository")
    }

    void "a missing body file fails with the I/O exit code"() {
        when:
        int code = run("append", "--repo", repo.toString(), "--body-file", repo.resolve("nope.md").toString())

        then:
        code == ExitCode.IO_FAILURE
    }
}
