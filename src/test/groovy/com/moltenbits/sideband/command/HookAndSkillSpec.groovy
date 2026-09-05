package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.capture.HumanCapture
import com.moltenbits.sideband.home.SidebandHome
import com.moltenbits.sideband.host.HostEnvironment
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.recipient.RecipientState
import io.micronaut.serde.ObjectMapper
import picocli.CommandLine

import java.nio.file.Files
import java.nio.file.Path

class HookAndSkillSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")
    Role detectedAgent
    Map extraPayload = [:]

    def setup() {
        run("init", "--repo", repo.toString(), "--human", "james", "--skip-clients")
        stdout = new StringWriter()
    }

    int hook(String prompt, String sessionId = "s1", String cwd = repo.toString(), List<String> args = []) {
        InputStream original = System.in
        Map payload = [prompt: prompt, cwd: cwd] + extraPayload
        if (sessionId != null) payload.session_id = sessionId
        System.in = new ByteArrayInputStream(context.getBean(ObjectMapper).writeValueAsString(payload).bytes)
        try {
            HostEnvironment host = Stub() {
                role() >> Optional.ofNullable(detectedAgent)
            }
            def command = new HookCommand.Prompt(context.getBean(SidebandHome), host,
                    context.getBean(RecipientState), context.getBean(HumanCapture), context.getBean(ObjectMapper))
            CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true)
            cli.out = new PrintWriter(stdout, true)
            cli.err = new PrintWriter(stderr, true)
            return cli.execute(args as String[])
        } finally {
            System.in = original
        }
    }

    void "the hook journals a prompt for the session that owns the Claude cursor and tells the model so"() {
        given:
        run("activate", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        when:
        int code = hook("@codex please look at this")

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.hookEventName == "UserPromptSubmit"
        json().hookSpecificOutput.additionalContext.contains("delivered it: codex=no-session")
        Files.readString(journalFile).contains("@codex please look at this")
    }

    void "the hook stays silent and writes nothing when the session does not own the cursor, or the prompt is not a human message"() {
        given:
        run("activate", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook(prompt, session, plainDirectory ? TempRepo.plainDirectory().toString() : repo.toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        prompt                          | session | plainDirectory
        "hello"                         | "other" | false
        "hello"                         | null    | false
        "[Sideband message]\n{...}"     | "s1"    | false
        "/sideband status"              | "s1"    | false
        "! sideband doctor"             | "s1"    | false
        "   "                           | "s1"    | false
        "hello"                         | "s1"    | true
    }

    void "the hook never fails the prompt, even on garbage input"() {
        given:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("not json".bytes)

        expect:
        run("hook", "prompt") == ExitCode.OK
        stderr.toString().contains("unreadable payload")

        cleanup:
        System.in = original
    }

    void "the same hook captures Codex prompts verbatim and never redelivers them to the originating turn"() {
        given:
        run("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-session")
        stdout = new StringWriter()
        String prompt = "  Please check café\nwith trailing spaces  "

        when:
        int code = hook(prompt, "codex-session")

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.hookEventName == "UserPromptSubmit"
        json().hookSpecificOutput.additionalContext.contains("Do not capture")

        when:
        stdout = new StringWriter()
        run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "1")

        then:
        json().entries.size() == 1
        json().entries[0].body == prompt
        json().entries[0].metadata.via == "codex"
        json().entries[0].metadata.from == "human:james"

        when:
        stdout = new StringWriter()
        run("pending", "--repo", repo.toString(), "--role", "codex")

        then:
        json().live == []
    }

    void "Codex delivered envelopes are never recaptured"() {
        given:
        run("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-session")
        stdout = new StringWriter()

        expect:
        hook("  [Sideband message]\n{\"entries\":[]}", "codex-session") == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)
    }

    void "automatic markers, unique session fallback and explicit agent all preserve the correct author and route"() {
        given:
        detectedAgent = detected
        extraPayload = [hook_event_name: "UserPromptSubmit", turn_id: "turn-1", model: "host-model", transcript_path: null]
        run("activate", "--repo", repo.toString(), "--role", owner, "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook("@${peer} hello\n", "s1", repo.toString(), flag ? ["--agent", flag] : []) == ExitCode.OK
        !stdout.toString().isEmpty()

        when:
        stdout = new StringWriter()
        run("wait", "--repo", repo.toString(), "--from", "0", "--timeout", "1")

        then:
        json().entries.size() == 1
        json().entries[0].metadata.via == owner
        json().entries[0].metadata.from == "human:james"
        json().entries[0].metadata.to == [peer]
        json().entries[0].body == "@${peer} hello\n"

        where:
        detected    | owner    | peer     | flag
        Role.CODEX  | "codex"  | "claude" | null
        Role.CLAUDE | "claude" | "codex"  | null
        null        | "codex"  | "claude" | null
        null        | "claude" | "codex"  | null
        Role.CLAUDE | "codex"  | "claude" | "codex"
        Role.CODEX  | "claude" | "codex"  | "claude"
    }

    void "caller hints never bypass session ownership and ambiguous fallback captures nothing"() {
        given:
        detectedAgent = detected
        run("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        if (ambiguous) run("activate", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook("hello", session, repo.toString(), flag ? ["--agent", flag] : []) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        detected    | flag     | session | ambiguous
        Role.CODEX  | null     | "other" | false
        Role.CODEX  | "codex"  | "other" | false
        Role.CODEX  | "claude" | "s1"    | false
        Role.CLAUDE | null     | "s1"    | false
        null        | null     | "s1"    | true
        null        | "codex"  | null    | false
    }

    void "a non-submit event and invalid working directory cannot capture or block a prompt"() {
        given:
        extraPayload = [hook_event_name: event]
        run("activate", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook("hello", "s1", invalidPath ? "bad\u0000path" : repo.toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        event              | invalidPath
        "Stop"             | false
        "UserPromptSubmit" | true
    }

    void "skill prints the embedded instructions for the named client as plain text"() {
        when:
        int code = run("skill", "--client", "codex")

        then:
        code == ExitCode.OK
        stdout.toString() == Files.readString(Path.of("skills/sideband-codex/INSTRUCTIONS.md"))
    }
}
