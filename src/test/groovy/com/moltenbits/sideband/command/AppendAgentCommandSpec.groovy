package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

class AppendAgentCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()

    Path body(String text) {
        Files.writeString(repo.resolve("body.md"), text)
    }

    String captureHuman(String text) {
        run("capture-human", "--repo", repo.toString(), "--via", "claude", "--body-file", body(text).toString())
        String id = json().metadata.id
        stdout = new StringWriter()
        id
    }

    void "a delegation caused by a human instruction is appended as a request"() {
        given:
        String humanId = captureHuman("review the change and ask Codex to test concurrency")

        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "--type", "request",
                "--caused-by", humanId, "--body-file", body("independently test the concurrency behavior").toString())

        then:
        code == ExitCode.OK
        with(json().metadata) {
            from == "claude"
            via == null
            to == ["codex"]
            type == "request"
            route == "direct"
            caused_by == humanId
            reply_to == null
            expects_reply == true
        }
        json().pushes*.outcome == ["no-session"]
    }

    void "a reply defaults to non-actionable and may address the human"() {
        given:
        String humanId = captureHuman("@codex review this")

        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "operator", "--type", "reply",
                "--reply-to", humanId, "--body-file", body("Looks fine.").toString())

        then:
        code == ExitCode.OK
        json().metadata.to == ["operator"]
        json().metadata.expects_reply == false
    }

    void "expects-reply can be set explicitly"() {
        given:
        String humanId = captureHuman("@codex review this")

        when:
        run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "reply",
                "--reply-to", humanId, "--expects-reply", "true", "--body-file", body("Which ordering did you expect?").toString())

        then:
        json().metadata.expects_reply == true
    }

    void "an actionable agent message with no human ancestor is refused"() {
        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "--type", "request",
                "--body-file", body("do some work").toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("human-authored")
        !Files.exists(repo.resolve(".git/sideband/journal.md"))
    }

    void "an actionable agent message pointing at a missing ancestor is refused"() {
        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "--type", "request",
                "--caused-by", "ghost", "--body-file", body("do some work").toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("missing ancestor")
    }

    void "a status with no links is fine because it is not actionable"() {
        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "status",
                "--body-file", body("still running the suite").toString())

        then:
        code == ExitCode.OK
        json().metadata.expects_reply == false
    }

    void "a reply without reply-to is invalid input"() {
        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "reply",
                "--body-file", body("done").toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("reply_to")
    }

    void "the former instruction type is no longer a type an agent can name"() {
        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "instruction",
                "--body-file", body("do this").toString())

        then:
        code == ExitCode.INVALID_INPUT
        stderr.toString().contains("instruction")
    }

    void "an ack names the request it acknowledges, expects nothing back, and may carry no body"() {
        given:
        String humanId = captureHuman("@codex review this")

        when:
        int code = run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "operator", "--type", "ack",
                "--reply-to", humanId, "--body-file", body("starting the review").toString())

        then:
        code == ExitCode.OK
        json().metadata.type == "ack"
        json().metadata.expects_reply == false
        json().body == "starting the review"

        expect: "without reply-to or with expects-reply it is invalid input"
        run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "ack",
                "--body-file", body("x").toString()) == ExitCode.INVALID_INPUT
        run("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "ack",
                "--reply-to", humanId, "--expects-reply", "true", "--body-file", body("x").toString()) == ExitCode.INVALID_INPUT
    }

    void "multiple recipients make a broadcast"() {
        given:
        String humanId = captureHuman("@all do the thing")

        when:
        run("append-agent", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "operator", "--type", "status",
                "--reply-to", humanId, "--body-file", body("finished my half").toString())

        then:
        json().metadata.route == "broadcast"
        json().metadata.to == ["codex", "operator"]
    }
}
