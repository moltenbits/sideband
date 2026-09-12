package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import io.micronaut.serde.ObjectMapper

import java.nio.file.Path

/**
 * The notify hook is the gate a host's notifier consults: exit 0 lets a notification through,
 * exit 1 holds it. Only turn ends that are not the operator's business are held; everything
 * else passes, and so does everything when Sideband is not in use in the calling session.
 */
class NotifyHookSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path stateDir = repo.resolve(".git/sideband")

    def setup() {
        run("init", "--repo", repo.toString(), "--skip-clients")
        stdout = new StringWriter()
    }

    int gate(Map payload, List<String> args = []) {
        InputStream original = System.in
        System.in = new ByteArrayInputStream(context.getBean(ObjectMapper).writeValueAsString(payload).bytes)
        try {
            stderr = new StringWriter()
            return run((["hook", "notify"] + args) as String[])
        } finally {
            System.in = original
        }
    }

    Map stop(String sessionId = "s1") {
        [hook_event_name: "Stop", session_id: sessionId, cwd: repo.toString(), stop_hook_active: false]
    }

    void human(String body = "fix the build", Role via = Role.CLAUDE) {
        context.getBean(Journal).append(stateDir, Fixtures.humanDraft(body, [ParticipantId.of(via)], via))
    }

    void "a turn end in the operator's client with nothing open passes, with nothing on stdout"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        stdout = new StringWriter()

        expect:
        gate(stop()) == ExitCode.OK
        stdout.toString().isEmpty()
        stderr.toString().contains("notification passed: nothing is open")
    }

    void "a turn end while a request to the other client is open is held, and says why"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        context.getBean(Journal).append(stateDir, Fixtures.agentDraft([causedBy: null]))

        expect:
        gate(stop()) == ExitCode.HELD
        stderr.toString().contains("sideband hook: notification held: 1 open request")
    }

    void "the calling client is recognized by its session id, and --agent overrides that"() {
        given: "Codex holds the prompt; Claude's turn end is not the moment"
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "claude-session")
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-thread")
        human("do it", Role.CODEX)

        expect:
        gate(stop("claude-session")) == ExitCode.HELD
        stderr.toString().contains("typed into Codex, not Claude")
        gate(stop("codex-thread")) == ExitCode.OK

        and: "the override wins over the lookup"
        gate(stop("claude-session"), ["--agent", "codex"]) == ExitCode.OK
    }

    void "a session Sideband is not joined in gets every notification, as it would without Sideband"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        context.getBean(Journal).append(stateDir, Fixtures.agentDraft([causedBy: null]))

        expect:
        gate(stop("some-other-session")) == ExitCode.OK
        stderr.toString().contains("not in use in session some-other-session")
    }

    void "a repository without Sideband, or a payload that cannot be read, passes"() {
        expect:
        gate([hook_event_name: "Stop", session_id: "s1", cwd: TempRepo.plainDirectory().toString()]) == ExitCode.OK

        when:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("not json".bytes)
        int code = run("hook", "notify")
        System.in = original

        then:
        code == ExitCode.OK
        stderr.toString().contains("unreadable payload")
    }

    void "a turn end rings once: the idle reminder that repeats it is held, and passes only where Sideband is not in use"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()

        expect:
        gate(stop()) == ExitCode.OK
        gate([hook_event_name: "Notification", notification_type: "idle_prompt", session_id: "s1", cwd: repo.toString()]) == ExitCode.HELD
        stderr.toString().contains("idle reminder")
        gate([hook_event_name: "Notification", notification_type: "idle_prompt", session_id: "elsewhere", cwd: repo.toString()]) == ExitCode.OK
    }

    void "only turn ends are held: an idle prompt is one, a permission prompt and any other event are not"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        context.getBean(Journal).append(stateDir, Fixtures.agentDraft([causedBy: null]))

        expect:
        gate([hook_event_name: event, notification_type: type, session_id: "s1", cwd: repo.toString()]) == (passes ? ExitCode.OK : ExitCode.HELD)

        where:
        event               | type                | passes
        "Stop"              | null                | false
        "Notification"      | "idle_prompt"       | false
        "Notification"      | "permission_prompt" | true
        "Notification"      | null                | true
        "PermissionRequest" | null                | true
        "SubagentStop"      | null                | true
    }

    void "on a prompt the operator's own words pass but a delivered envelope or a host notice is held"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")

        expect:
        gate([hook_event_name: "UserPromptSubmit", prompt: prompt, session_id: "s1", cwd: repo.toString()]) == (passes ? ExitCode.OK : ExitCode.HELD)

        where:
        prompt                                                                                             | passes
        "please look at this"                                                                              | true
        "/sideband status"                                                                                 | true
        "[Sideband message]\n{...}"                                                                        | false
        "<cross-session-message from-name=\"Codex\">\n[Sideband message]\n{...}\n</cross-session-message>" | false
        "<task-notification>\n<task-id>b1</task-id>\n</task-notification>"                                 | false
    }
}
