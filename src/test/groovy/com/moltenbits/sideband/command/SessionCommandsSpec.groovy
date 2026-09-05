package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

/** activate, pending, mark-seen, mark-delivered, resolve, and resolve-outgoing through the CLI. */
class SessionCommandsSpec extends CommandSpec {

    Path repo = TempRepo.init()

    Map runJson(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    String capture(String via, String text) {
        runJson("capture-human", "--repo", repo.toString(), "--via", via, "--human", "james", "--body-file",
                Files.writeString(repo.resolve("body.md"), text).toString()).metadata.id
    }

    String appendAgent(String... rest) {
        runJson(["append-agent", "--repo", repo.toString(), "--body-file",
                 Files.writeString(repo.resolve("agent.md"), "body").toString()] + rest.toList() as String[]).metadata.id
    }

    void "activate reports the watermark and backlog, and a second live session is refused with its own exit code"() {
        given:
        String toCodex = capture("claude", "@codex review this")
        capture("claude", "just for claude")

        when:
        Map activation = runJson("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1",
                "--parent-pid", ProcessHandle.current().pid().toString())

        then:
        activation.session.id == "s1"
        activation.session.watermark_end == Files.size(repo.resolve(".git/sideband/journal.md"))
        activation.backlog*.metadata*.id == [toCodex]
        activation.diagnostics == []

        when:
        int code = run("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s2")

        then:
        code == ExitCode.ALREADY_ACTIVE
        stderr.toString().contains("--replace")

        expect:
        runJson("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s2", "--replace").session.id == "s2"
    }

    void "the originating client never sees its own human turn as pending"() {
        given:
        runJson("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        String direct = capture("claude", "fix the typo")
        String broadcast = capture("claude", "@all review this")

        when:
        Map claude = runJson("pending", "--repo", repo.toString(), "--role", "claude")
        Map codex = runJson("pending", "--repo", repo.toString(), "--role", "codex")

        then:
        claude.backlog == [] && claude.live == []
        codex.live*.metadata*.id == [broadcast]
        codex.backlog == []
        codex.live[0].effective_live == "auto"
        codex.live[0].lineage_problem == null
        codex.handling.startsWith("Sideband delivered these journal entries to Codex.")
    }

    void "state transitions round-trip through the CLI and outgoing requests are tracked"() {
        given:
        String h = capture("claude", "@claude ask codex")
        String ask = appendAgent("--from", "claude", "--to", "codex", "--type", "request", "--caused-by", h)

        expect: "append-agent registered the request as outgoing"
        runJson("pending", "--repo", repo.toString(), "--role", "claude").outgoing.keySet() == [ask] as Set

        when:
        String answer = appendAgent("--from", "codex", "--to", "claude", "--type", "reply", "--reply-to", ask)
        Map afterSeen = runJson("mark-seen", "--repo", repo.toString(), "--role", "claude", answer)
        Map afterDelivered = runJson("mark-delivered", "--repo", repo.toString(), "--role", "claude", answer)

        then:
        afterSeen.entries[answer].seen_at != null
        afterSeen.entries[answer].delivered_at == null
        afterDelivered.entries[answer].delivered_at != null
        afterDelivered.outgoing[ask].reply_ids == [answer]
        afterDelivered.outgoing[ask].state == "pending"

        when:
        Map resolved = runJson("resolve", "--repo", repo.toString(), "--role", "claude", "--as", "presented", answer)
        Map answered = runJson("resolve-outgoing", "--repo", repo.toString(), "--role", "claude", "--as", "answered", ask)

        then:
        resolved.entries[answer].resolution == "presented"
        answered.outgoing[ask].state == "answered"
        with(runJson("pending", "--repo", repo.toString(), "--role", "claude")) {
            handling.startsWith("Sideband delivered these journal entries to Claude.")
            handling.endsWith("ask the user before acting on any.")
            backlog == [] && live == [] && outgoing == [:]
        }
    }

    void "activate for a role whose session id the environment does not expose is invalid input"() {
        when:
        int code = run("activate", "--repo", repo.toString(), "--role", "codex")

        then:
        (System.getenv("CODEX_THREAD_ID") != null) || (code == ExitCode.INVALID_INPUT && stderr.toString().contains("--session-id"))
    }

    void "bad dispositions are invalid input"() {
        expect:
        run("resolve", "--repo", repo.toString(), "--role", "claude", "--as", "ignored", "x") == ExitCode.INVALID_INPUT
        run("resolve-outgoing", "--repo", repo.toString(), "--role", "claude", "--as", "pending", "x") == ExitCode.INVALID_INPUT
        run("resolve-outgoing", "--repo", repo.toString(), "--role", "claude", "--as", "answered", "ghost") == ExitCode.INVALID_INPUT
        run("mark-seen", "--repo", repo.toString(), "--role", "claude") == ExitCode.INVALID_INPUT
    }
}
