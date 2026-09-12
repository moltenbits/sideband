package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.Role
import io.micronaut.serde.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path

/**
 * The notify hook wraps a host's notifier command and runs it only for turn ends that are the
 * operator's business; everything that is not a turn end passes through, and so does everything
 * when Sideband is not in use in the calling session.
 */
class NotifyHookSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path stateDir = repo.resolve(".git/sideband")
    Path received = repo.resolve("received.json")
    String notifier = "cat > '" + received + "'"

    def setup() {
        run("init", "--repo", repo.toString(), "--skip-clients")
        stdout = new StringWriter()
    }

    int hook(Map payload, List<String> args = ["--run", notifier]) {
        InputStream original = System.in
        System.in = new ByteArrayInputStream(context.getBean(ObjectMapper).writeValueAsString(payload).bytes)
        try {
            return run((["hook", "notify"] + args) as String[])
        } finally {
            System.in = original
        }
    }

    Map stop(String sessionId = "s1") {
        [hook_event_name: "Stop", session_id: sessionId, cwd: repo.toString(), stop_hook_active: false]
    }

    boolean ran() {
        Files.exists(received)
    }

    void human(String body = "fix the build", Role via = Role.CLAUDE) {
        context.getBean(Journal).append(stateDir, Fixtures.humanDraft(body, [com.moltenbits.sideband.protocol.ParticipantId.of(via)], via))
    }

    void "a turn end in the operator's client with nothing open runs the notifier with the payload it was given"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        stdout = new StringWriter()

        when:
        int code = hook(stop())

        then:
        code == ExitCode.OK
        ran()
        context.getBean(ObjectMapper).readValue(Files.readString(received), Map).hook_event_name == "Stop"
        stdout.toString().isEmpty()
    }

    void "a turn end while a request to the other client is open runs nothing and says why"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        context.getBean(Journal).append(stateDir, Fixtures.agentDraft([causedBy: null]))

        when:
        int code = hook(stop())

        then:
        code == ExitCode.OK
        !ran()
        stderr.toString().contains("sideband hook: notification held: 1 open request")
    }

    void "the calling client is recognized by its session id, and --agent overrides that"() {
        given: "Codex holds the prompt; Claude's turn end is not the moment"
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "claude-session")
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-thread")
        human("do it", Role.CODEX)

        expect:
        hook(stop("claude-session")) == ExitCode.OK
        !ran()
        stderr.toString().contains("typed into Codex, not Claude")

        and:
        hook(stop("codex-thread")) == ExitCode.OK
        ran()

        when:
        Files.delete(received)
        stderr = new StringWriter()

        then: "the override wins over the lookup"
        hook(stop("claude-session"), ["--agent", "codex", "--run", notifier]) == ExitCode.OK
        ran()
    }

    void "a session Sideband is not joined in gets every notification, as it would without Sideband"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        context.getBean(Journal).append(stateDir, Fixtures.agentDraft([causedBy: null]))

        expect:
        hook(stop("some-other-session")) == ExitCode.OK
        ran()
        stderr.toString().contains("not in use in session some-other-session")
    }

    void "a repository without Sideband, or a payload that cannot be read, passes straight through"() {
        expect:
        hook([hook_event_name: "Stop", session_id: "s1", cwd: TempRepo.plainDirectory().toString()]) == ExitCode.OK
        ran()

        when:
        Files.delete(received)
        InputStream original = System.in
        System.in = new ByteArrayInputStream("not json".bytes)
        int code = run("hook", "notify", "--run", notifier)
        System.in = original

        then:
        code == ExitCode.OK
        Files.readString(received) == "not json"
    }

    void "a turn end rings once: the idle reminder that repeats it is held, and passes through only where Sideband is not in use"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()

        expect:
        hook(stop()) == ExitCode.OK
        ran()

        when:
        Files.delete(received)
        stderr = new StringWriter()

        then:
        hook([hook_event_name: "Notification", notification_type: "idle_prompt", session_id: "s1", cwd: repo.toString()]) == ExitCode.OK
        !ran()
        stderr.toString().contains("idle reminder")

        and:
        hook([hook_event_name: "Notification", notification_type: "idle_prompt", session_id: "elsewhere", cwd: repo.toString()]) == ExitCode.OK
        ran()
    }

    void "the notifier's stdout goes to stderr, never to the host's decision channel, and the hook's own stdout stays empty"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        stdout = new StringWriter()
        PrintStream original = System.out
        ByteArrayOutputStream processOut = new ByteArrayOutputStream()
        System.out = new PrintStream(processOut, true)

        when:
        int code = hook(stop(), ["--run", "cat > /dev/null; echo '{\"decision\":\"block\"}'"])

        then:
        code == ExitCode.OK
        stdout.toString().isEmpty()
        processOut.toString().isEmpty()
        stderr.toString().contains('{"decision":"block"}')

        cleanup:
        System.out = original
    }

    void "only turn ends are held: an idle prompt is one, a permission prompt and any other event are not"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        context.getBean(Journal).append(stateDir, Fixtures.agentDraft([causedBy: null]))

        expect:
        hook([hook_event_name: event, notification_type: type, session_id: "s1", cwd: repo.toString()]) == ExitCode.OK
        ran() == forwarded

        where:
        event               | type                | forwarded
        "Stop"              | null                | false
        "Notification"      | "idle_prompt"       | false
        "Notification"      | "permission_prompt" | true
        "Notification"      | null                | true
        "PermissionRequest" | null                | true
        "SubagentStop"      | null                | true
    }

    void "on a prompt the notifier runs for the operator's own words but not for a delivered envelope or a host notice"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")

        expect:
        hook([hook_event_name: "UserPromptSubmit", prompt: prompt, session_id: "s1", cwd: repo.toString()]) == ExitCode.OK
        ran() == forwarded

        where:
        prompt                                                                                             | forwarded
        "please look at this"                                                                              | true
        "/sideband status"                                                                                 | true
        "[Sideband message]\n{...}"                                                                        | false
        "<cross-session-message from-name=\"Codex\">\n[Sideband message]\n{...}\n</cross-session-message>" | false
        "<task-notification>\n<task-id>b1</task-id>\n</task-notification>"                                 | false
    }

    @spock.lang.Timeout(20)
    void "a notifier that speaks a lot before it reads, given a large payload, still finishes"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()
        String large = "x" * (1024 * 1024)
        String talkative = "head -c 1048576 /dev/zero | tr '\\0' y; cat > '" + received + "'"

        when:
        int code = hook(stop() + [last_assistant_message: large], ["--run", talkative])

        then:
        code == ExitCode.OK
        stderr.toString().count("y") == 1024 * 1024
        Files.readString(received).contains(large)
    }

    void "a notifier that fails never fails the hook"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        human()

        expect:
        hook(stop(), ["--run", "exit 3"]) == ExitCode.OK
        stderr.toString().contains("sideband hook: notifier exited 3")
    }
}
