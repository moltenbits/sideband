package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

/** activate, pending, and ack through the CLI. */
class SessionCommandsSpec extends CommandSpec {

    Path repo = TempRepo.init()

    Map runJson(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    String capture(String via, String text) {
        runJson("capture-human", "--repo", repo.toString(), "--via", via, "--body-file",
                Files.writeString(repo.resolve("body.md"), text).toString()).metadata.id
    }

    String appendAgent(String... rest) {
        runJson(["append-agent", "--repo", repo.toString(), "--body-file",
                 Files.writeString(repo.resolve("agent.md"), "body").toString()] + rest.toList() as String[]).metadata.id
    }

    void "activate starts the session and lists what predates it; a second live session is refused with its own exit code"() {
        given:
        String toCodex = capture("claude", "@codex review this")
        capture("claude", "just for claude")

        when:
        Map activation = runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1",
                "--parent-pid", ProcessHandle.current().pid().toString())

        then:
        activation.session.id == "s1"
        activation.session.watermark == Files.size(repo.resolve(".git/sideband/journal.md"))
        activation.session.offset == activation.session.watermark
        activation.open*.entry*.metadata*.id == [toCodex]
        activation.open*.before_session == [true]
        activation.in_progress == []
        activation.updates == []
        activation.outgoing == []
        activation.diagnostics == []
        activation.intent == "Sideband delivery; use the Sideband skill (\$sideband) for handling instructions"

        when:
        int code = run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s2")

        then:
        code == ExitCode.ALREADY_ACTIVE
        stderr.toString().contains("--replace")

        expect:
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s2", "--replace").session.id == "s2"
    }

    void "the originating client never sees its own human turn"() {
        given:
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        capture("claude", "fix the typo")
        String broadcast = capture("claude", "@all review this")

        when:
        Map claude = runJson("pending", "--repo", repo.toString(), "--role", "claude")
        Map codex = runJson("pending", "--repo", repo.toString(), "--role", "codex")

        then:
        claude.open == [] && claude.updates == []
        codex.open*.entry*.metadata*.id == [broadcast]
        codex.open[0].before_session == false
        codex.open[0].entry.effective_live == "auto"
        codex.open[0].entry.lineage_problem == null
    }

    void "an ack moves a request to in progress for the recipient and shows on the sender's outgoing request; a reply closes both"() {
        given:
        String h = capture("claude", "@claude ask codex")
        String ask = appendAgent("--from", "claude", "--to", "codex", "--type", "request", "--caused-by", h)

        expect: "the sender sees its request awaiting a reply, unacknowledged"
        with(runJson("pending", "--repo", repo.toString(), "--role", "claude").outgoing) {
            size() == 1
            it[0].id == ask
            it[0].acknowledged_at == null
            it[0].silence_seconds >= 0
        }
        runJson("pending", "--repo", repo.toString(), "--role", "codex").open*.entry*.metadata*.id == [ask]

        when:
        Map ack = runJson("append-agent", "--repo", repo.toString(), "--from", "codex", "--to", "claude", "--type", "ack", "--reply-to", ask)

        then: "an ack needs no body, never expects a reply, and is never pushed"
        ack.metadata.type == "ack"
        ack.metadata.expects_reply == false
        ack.body == "received"
        ack.pushes == []
        runJson("pending", "--repo", repo.toString(), "--role", "claude").outgoing[0].acknowledged_at != null
        runJson("pending", "--repo", repo.toString(), "--role", "claude").outgoing[0].ack_ids == [ack.metadata.id]
        runJson("pending", "--repo", repo.toString(), "--role", "codex").open == []
        runJson("pending", "--repo", repo.toString(), "--role", "codex").in_progress*.entry*.metadata*.id == [ask]

        when:
        String answer = appendAgent("--from", "codex", "--to", "claude", "--type", "reply", "--reply-to", ask)

        then:
        runJson("pending", "--repo", repo.toString(), "--role", "claude").outgoing == []
        runJson("pending", "--repo", repo.toString(), "--role", "codex").in_progress == []
        runJson("pending", "--repo", repo.toString(), "--role", "claude").updates*.metadata*.id == [answer]
    }

    void "a reply that arrived while the role was away is shown by join --resume and hidden by a plain join"() {
        given:
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "away", "--parent-pid", "999999999")
        String h = capture("codex", "ask claude")
        String ask = appendAgent("--from", "codex", "--to", "claude", "--type", "request", "--caused-by", h)
        String answer = appendAgent("--from", "claude", "--to", "codex", "--type", "reply", "--reply-to", ask)

        expect:
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "back", "--resume").updates*.metadata*.id == [answer]
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "back", "--resume").session.resumed == true
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "back", "--resume").updates == []
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "again", "--replace").updates == []
    }

    void "pending advances the read position, so an update is shown once"() {
        given:
        runJson("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        appendAgent("--from", "codex", "--to", "claude", "--type", "status")

        expect:
        runJson("pending", "--repo", repo.toString(), "--role", "claude").updates.size() == 1
        runJson("pending", "--repo", repo.toString(), "--role", "claude").updates.size() == 0
    }

    void "activate for a role whose session id the environment does not expose is invalid input"() {
        when:
        int code = run("join", "--repo", repo.toString(), "--role", "codex")

        then:
        (System.getenv("CODEX_THREAD_ID") != null) || (code == ExitCode.INVALID_INPUT && stderr.toString().contains("--session-id"))
    }

    void "the cursor commands are gone"() {
        expect:
        run("mark-delivered", "--repo", repo.toString(), "x") != ExitCode.OK
        run("resolve", "--repo", repo.toString(), "--as", "acted", "x") != ExitCode.OK
        run("resolve-outgoing", "--repo", repo.toString(), "--as", "answered", "x") != ExitCode.OK
    }
}
