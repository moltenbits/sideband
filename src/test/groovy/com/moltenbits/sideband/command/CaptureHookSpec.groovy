package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.capture.AgentCapture
import com.moltenbits.sideband.home.SidebandHome
import com.moltenbits.sideband.project.Projects
import com.moltenbits.sideband.host.HostEnvironment
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Role
import io.micronaut.serde.ObjectMapper
import picocli.CommandLine

import java.nio.file.Files
import java.nio.file.Path

/** `sideband hook capture`: the turn-end, interruption and session-end hook that records what the agent said. */
class CaptureHookSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path stateDir = home.resolve(".sideband")
    Path transcript = Files.createTempFile("transcript", ".jsonl")
    ObjectMapper json = context.getBean(ObjectMapper)

    def setup() {
        run("init", "--repo", repo.toString(), "--skip-clients")
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "c1")
        stdout = new StringWriter()
    }

    String line(Map record) {
        json.writeValueAsString(record) + "\n"
    }

    void claudeSays(String promptId, String... finals) {
        Files.writeString(transcript, Files.readString(transcript)
                + line([type: "user", promptId: promptId, message: [content: "go"]])
                + finals.collect { line([type: "assistant", message: [model: "m", stop_reason: "end_turn", content: [[type: "text", text: it]]]]) }.join(""))
    }

    int capture(Role agent, Map payload) {
        InputStream original = System.in
        System.in = new ByteArrayInputStream(json.writeValueAsString([cwd: repo.toString(), transcript_path: transcript.toString()] + payload).bytes)
        try {
            HostEnvironment host = Stub() {
                role() >> Optional.empty()
                process(_) >> Optional.empty()
            }
            def command = new HookCommand.Capture(context.getBean(SidebandHome), context.getBean(Projects), host, context.getBean(AgentCapture), json)
            CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true)
            cli.out = new PrintWriter(stdout, true)
            cli.err = new PrintWriter(stderr, true)
            return cli.execute("--agent", agent.id())
        } finally {
            System.in = original
        }
    }

    List<Entry> captured() {
        context.getBean(Journal).readAfter(stateDir, 0).entries().findAll { it.metadata().isCapturedTurn() }
    }

    void "a Claude Code turn end records what the agent said, and tells the host nothing"() {
        given:
        claudeSays("p-1", "All clear.")

        when:
        int code = capture(Role.CLAUDE, [hook_event_name: "Stop", session_id: "s1", prompt_id: "p-1",
                last_assistant_message: "All clear.", stop_hook_active: false])

        then:
        code == ExitCode.OK
        stdout.toString().isEmpty()
        captured()*.body() == ["All clear."]
        captured()[0].metadata().from() == Fixtures.CLAUDE
        stderr.toString().contains("recorded 1")
    }

    void "a Codex interruption records what was said so far, under the turn the payload names"() {
        given:
        Files.writeString(transcript,
                line([type: "event_msg", payload: [type: "task_started", turn_id: "turn-1"]]) +
                        line([type: "response_item", payload: [type: "message", role: "assistant", phase: "commentary",
                                content: [[type: "output_text", text: "Halfway there."]]]]))

        when:
        int code = capture(Role.CODEX, [hook_event_name: "Interrupt", session_id: "c1", turn_id: "turn-1"])

        then:
        code == ExitCode.OK
        captured()*.body() == ["Halfway there."]
        captured()[0].metadata().from() == Fixtures.CODEX
    }

    void "a session's end records what the last capture has not, though the payload names no turn"() {
        given: "a turn end whose final the host wrote too late, after the session's starting point was set"
        capture(Role.CLAUDE, [hook_event_name: "SessionEnd", session_id: "s1", reason: "other"])
        claudeSays("p-1", "Written late.")

        when:
        int code = capture(Role.CLAUDE, [hook_event_name: "SessionEnd", session_id: "s1", reason: "clear"])

        then:
        code == ExitCode.OK
        captured()*.body() == ["Written late."]
    }

    void "a subagent's hook, an event it is not registered for, or a repository without Sideband records nothing"() {
        given:
        claudeSays("p-1", "Not this.")

        when:
        int subagent = capture(Role.CLAUDE, [hook_event_name: "Stop", session_id: "s1", prompt_id: "p-1", agent_id: "a-1",
                last_assistant_message: "Not this."])
        int other = capture(Role.CLAUDE, [hook_event_name: "PreToolUse", session_id: "s1", prompt_id: "p-1"])
        int elsewhere = capture(Role.CLAUDE, [hook_event_name: "Stop", session_id: "s1", prompt_id: "p-1",
                cwd: Files.createTempDirectory("plain").toString(), last_assistant_message: "Not this."])

        then:
        [subagent, other, elsewhere].every { it == ExitCode.OK }
        captured().isEmpty()
    }

    void "a payload that cannot be read, or names no session, never blocks the host"() {
        when:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("not json".bytes)
        int unreadable
        try {
            def command = new HookCommand.Capture(context.getBean(SidebandHome), context.getBean(Projects), Stub(HostEnvironment), context.getBean(AgentCapture), json)
            CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true)
            cli.err = new PrintWriter(stderr, true)
            unreadable = cli.execute("--agent", "claude")
        } finally {
            System.in = original
        }
        int sessionless = capture(Role.CLAUDE, [hook_event_name: "Stop", prompt_id: "p-1", last_assistant_message: "x"])

        then:
        unreadable == ExitCode.OK
        sessionless == ExitCode.OK
        stderr.toString().contains("capture skipped")
        captured().isEmpty()
    }

    void "a failure inside the capture is reported on stderr and never blocks the host"() {
        given:
        AgentCapture failing = Stub() {
            turnEnded(*_) >> { throw new UncheckedIOException(new IOException("disk full")) }
        }
        InputStream original = System.in
        System.in = new ByteArrayInputStream(json.writeValueAsString([cwd: repo.toString(), transcript_path: transcript.toString(),
                hook_event_name: "Stop", session_id: "s1", prompt_id: "p-1", last_assistant_message: "x"]).bytes)

        when:
        int code
        try {
            HostEnvironment host = Stub() { process(_) >> Optional.empty() }
            def command = new HookCommand.Capture(context.getBean(SidebandHome), context.getBean(Projects), host, failing, json)
            CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true)
            cli.err = new PrintWriter(stderr, true)
            code = cli.execute("--agent", "claude")
        } finally {
            System.in = original
        }

        then:
        code == ExitCode.OK
        stderr.toString().contains("disk full")
    }
}
