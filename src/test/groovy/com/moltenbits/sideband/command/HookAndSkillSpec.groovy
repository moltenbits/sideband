package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.capture.HumanCapture
import com.moltenbits.sideband.home.SidebandHome
import com.moltenbits.sideband.host.HostEnvironment
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.pending.Pending
import com.moltenbits.sideband.session.Sessions
import io.micronaut.serde.ObjectMapper
import picocli.CommandLine

import java.nio.file.Files
import java.nio.file.Path

class HookAndSkillSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")
    Role detectedAgent
    Long detectedPid
    Map extraPayload = [:]
    HumanCapture captureOverride

    def setup() {
        run("init", "--repo", repo.toString(), "--skip-clients")
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
                parentPid(_) >> Optional.ofNullable(detectedPid)
            }
            def command = new HookCommand.Prompt(context.getBean(SidebandHome), host,
                    context.getBean(Sessions), context.getBean(Pending), captureOverride ?: context.getBean(HumanCapture), context.getBean(ObjectMapper))
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
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
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
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook(prompt, session, plainDirectory ? TempRepo.plainDirectory().toString() : repo.toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        prompt                          | session | plainDirectory
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
        json().hookSpecificOutput.additionalContext.startsWith("Sideband could not journal this prompt")

        cleanup:
        System.in = original
    }

    void "the same hook captures Codex prompts verbatim and never redelivers them to the originating turn"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-session")
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
        json().entries[0].metadata.from == "operator"

        when:
        stdout = new StringWriter()
        run("pending", "--repo", repo.toString(), "--role", "codex")

        then:
        json().open == [] && json().in_progress == [] && json().updates == []
    }

    void "Codex delivered envelopes are never recaptured"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-session")
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
        run("join", "--repo", repo.toString(), "--role", owner, "--session-id", "s1")
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
        json().entries[0].metadata.from == "operator"
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
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        if (ambiguous) run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook("hello", session, repo.toString(), flag ? ["--agent", flag] : []) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        detected    | flag     | session | ambiguous
        Role.CODEX  | "claude" | "s1"    | false
        Role.CLAUDE | null     | "s1"    | false
    }

    void "a non-submit event and invalid working directory cannot capture or block a prompt"() {
        given:
        extraPayload = [hook_event_name: event]
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
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

    void "skill --eject writes the instructions into the installed SKILL.md and reports it"() {
        given:
        Path home = Files.createTempDirectory("home")

        when:
        int code = run("skill", "--client", "codex", "--home", home.toString(), "--eject")

        then:
        code == ExitCode.OK
        json().state == "ejected"
        json().name == "codex"
        Files.readString(home.resolve(".agents/skills/sideband/SKILL.md")).endsWith(Files.readString(Path.of("skills/sideband-codex/INSTRUCTIONS.md")))
    }

    void "skill prints the embedded instructions for the named client as plain text"() {
        when:
        int code = run("skill", "--client", "codex")

        then:
        code == ExitCode.OK
        stdout.toString() == Files.readString(Path.of("skills/sideband-codex/INSTRUCTIONS.md"))
    }

    void "a resumed conversation refreshes its dead process before capturing without reactivation"() {
        given:
        detectedAgent = detected
        detectedPid = ProcessHandle.current().pid()
        run("join", "--repo", repo.toString(), "--role", owner, "--session-id", "s1", "--parent-pid", "999999999")
        Sessions state = context.getBean(Sessions)
        Path dir = context.getBean(SidebandHome).locate(repo)
        def before = state.load(dir, Role.valueOf(owner.toUpperCase())).get()
        stdout = new StringWriter()

        when:
        int code = hook("first prompt after resume", "s1", repo.toString(), flag ? ["--agent", flag] : [])

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.additionalContext.contains("Sideband journaled this prompt")
        with(state.load(dir, Role.valueOf(owner.toUpperCase())).get()) {
            parentPid() == detectedPid
            id() == before.id()
            startedAt() == before.startedAt()
            watermark() == before.watermark()
            offset() == before.offset()
        }

        where:
        detected    | owner    | flag
        Role.CODEX  | "codex"  | null
        null        | "codex"  | null
        Role.CLAUDE | "codex"  | "codex"
        Role.CLAUDE | "claude" | null
        null        | "claude" | null
    }

    void "a dead recorded process is not revived without a living identified caller and explains skipped capture"() {
        given:
        detectedAgent = Role.CODEX
        detectedPid = caller
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1", "--parent-pid", "999999999")
        stdout = new StringWriter()

        expect:
        hook("resume", "s1") == ExitCode.OK
        json().hookSpecificOutput.additionalContext.startsWith("Sideband could not journal this prompt")
        json().hookSpecificOutput.additionalContext.contains("caller")
        stderr.toString().contains("capture failed")
        !Files.exists(journalFile)

        where:
        caller << [null, 999999998L]
    }

    void "a prompt that should have been journaled and was not is reported to the model, never only to stderr"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()
        Files.createDirectories(journalFile)

        expect: "a directory where the journal belongs makes the append itself fail, so the outcome is uncertain"
        hook("this must not vanish") == ExitCode.OK
        json().hookSpecificOutput.hookEventName == "UserPromptSubmit"
        json().hookSpecificOutput.additionalContext.startsWith("Sideband may not have journaled this prompt")
        json().hookSpecificOutput.additionalContext.endsWith("Do not capture it again unless the journal tail shows it is missing.")
        stderr.toString().contains("capture failed during the append")
    }

    void "a failure before the append says the prompt is not journaled and may be captured again"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        captureOverride = { Path dir, Role via, String body ->
            throw new com.moltenbits.sideband.capture.CaptureFailedException(
                    com.moltenbits.sideband.capture.CaptureFailedException.Stage.NOT_JOURNALED, null, new RuntimeException("routing exploded"))
        } as HumanCapture
        stdout = new StringWriter()

        expect: "a failure before the append leaves nothing written"
        hook("lost before the journal") == ExitCode.OK
        json().hookSpecificOutput.additionalContext.startsWith("Sideband could not journal this prompt")
        json().hookSpecificOutput.additionalContext.contains("It is not in the journal")
        json().hookSpecificOutput.additionalContext.contains("capture it with `sideband capture-human`")
        !Files.exists(journalFile)
    }

    void "a failure after the append reports the journaled id and forbids a second capture"() {
        given:
        detectedAgent = Role.CLAUDE
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        Path codexSession = Files.createDirectories(repo.resolve(".git/sideband/sessions/codex.json"))
        stdout = new StringWriter()

        when: "delivery to Codex cannot read its session record after the entry is in the journal"
        int code = hook("@codex journaled but not finished")
        String journal = Files.readString(journalFile)
        String id = (journal =~ /"id":"([0-9a-f-]{36})"/)[-1][1]

        then:
        code == ExitCode.OK
        journal.contains("journaled but not finished")
        json().hookSpecificOutput.additionalContext.startsWith("Sideband journaled this prompt as " + id + " but could not finish afterwards")
        json().hookSpecificOutput.additionalContext.contains("Do not capture it again")
        stderr.toString().contains("journaled " + id)

        cleanup:
        Files.deleteIfExists(codexSession)
    }

    void "a hook payload without a session id is reported only where Sideband is in use"() {
        given:
        detectedAgent = detected
        if (active) run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook("hello", null) == ExitCode.OK
        stderr.toString().contains("no session_id")
        stdout.toString().isEmpty() == !active
        !active || json().hookSpecificOutput.additionalContext.contains("no session_id")
        !Files.exists(journalFile)

        where:
        detected    | active
        Role.CLAUDE | true
        Role.CLAUDE | false
        null        | true
        null        | false
    }

    void "a marker-free caller that cannot be identified is reported when any role is active, and silent otherwise"() {
        given:
        if (active) run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1", "--parent-pid", "1")
        if (ambiguous) run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1", "--parent-pid", "1")
        stdout = new StringWriter()

        expect:
        hook("hello", session) == ExitCode.OK
        stderr.toString().contains(reason)
        stdout.toString().isEmpty() == !active
        !active || json().hookSpecificOutput.additionalContext.contains(reason)
        !Files.exists(journalFile)

        where:
        active | ambiguous | session | reason
        false  | false     | "s1"    | "no recorded session matches"
        true   | false     | "other" | "no recorded session matches"
        true   | true      | "s1"    | "multiple roles"
    }

    void "a cleared conversation in the same host process keeps its session and captures under the new id"() {
        given:
        detectedAgent = markers ? Role.CLAUDE : null
        detectedPid = ProcessHandle.current().pid()
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "before-clear",
                "--parent-pid", detectedPid.toString())
        stdout = new StringWriter()
        def before = context.getBean(Sessions).load(repo.resolve(".git/sideband"), Role.CLAUDE).get()

        when:
        int code = hook("first prompt after /clear", "after-clear")
        def after = context.getBean(Sessions).load(repo.resolve(".git/sideband"), Role.CLAUDE).get()

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.additionalContext.startsWith("Sideband journaled this prompt")
        Files.readString(journalFile).contains("first prompt after /clear")
        after.id() == "after-clear"
        after.parentPid() == detectedPid
        after.startedAt() == before.startedAt()
        after.watermark() == before.watermark()

        where:
        markers << [true, false]
    }

    void "a caller from a second session of an active role is told another session owns Sideband"() {
        given:
        detectedAgent = Role.CODEX
        detectedPid = ProcessHandle.current().pid()
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1", "--parent-pid", "1")
        stdout = new StringWriter()

        expect:
        hook("hello", "other", repo.toString(), flag ? ["--agent", flag] : []) == ExitCode.OK
        json().hookSpecificOutput.additionalContext.contains("another Codex session owns Sideband")
        !Files.exists(journalFile)

        where:
        flag << [null, "codex"]
    }

    void "when Sideband is not active for the caller, the model hears about it only if entries are waiting"() {
        given:
        detectedAgent = Role.CLAUDE
        if (waiting > 0) {
            run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "c1")
            (1..waiting).each { n ->
                context.getBean(com.moltenbits.sideband.journal.Journal).append(journalFile,
                        com.moltenbits.sideband.Fixtures.agentDraft(from: com.moltenbits.sideband.Fixtures.CODEX,
                                to: [com.moltenbits.sideband.Fixtures.CLAUDE], type: com.moltenbits.sideband.protocol.MessageType.STATUS,
                                causedBy: null, expectsReply: false, body: "status " + n))
            }
        }
        long before = Files.exists(journalFile) ? Files.size(journalFile) : 0
        stdout = new StringWriter()

        expect:
        hook("hello", "s1") == ExitCode.OK
        stderr.toString().contains("not joined as claude")
        (waiting == 0) == stdout.toString().isEmpty()
        waiting == 0 || json().hookSpecificOutput.additionalContext == expected
        (Files.exists(journalFile) ? Files.size(journalFile) : 0) == before

        where:
        waiting | expected
        0       | null
        1       | "Sideband is not active in this session and 1 entry addressed to Claude is waiting. Tell the user; /sideband joins and reviews them."
        2       | "Sideband is not active in this session and 2 entries addressed to Claude are waiting. Tell the user; /sideband joins and reviews them."
    }

    void "an envelope cannot revive a dead session"() {
        given:
        detectedAgent = Role.CODEX
        detectedPid = ProcessHandle.current().pid()
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1", "--parent-pid", "999999999")
        Path dir = context.getBean(SidebandHome).locate(repo)
        Sessions state = context.getBean(Sessions)
        def before = state.load(dir, Role.CODEX)
        stdout = new StringWriter()

        expect:
        hook("[Sideband message]\n{}") == ExitCode.OK
        stdout.toString().isEmpty()
        stderr.toString().isEmpty()
        state.load(dir, Role.CODEX) == before
        !Files.exists(journalFile)
    }

    void "ambiguous dead sessions are not refreshed by the fallback"() {
        given:
        detectedPid = ProcessHandle.current().pid()
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1", "--parent-pid", "999999999")
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1", "--parent-pid", "999999999")
        stdout = new StringWriter()

        expect: "both roles are active, so the failure to identify the caller is reported, not hidden"
        hook("resume") == ExitCode.OK
        json().hookSpecificOutput.additionalContext.contains("multiple roles")
        stderr.toString().contains("multiple roles")
        !Files.exists(journalFile)
        !context.getBean(Sessions).load(context.getBean(SidebandHome).locate(repo), Role.CODEX).get().isLive()
        !context.getBean(Sessions).load(context.getBean(SidebandHome).locate(repo), Role.CLAUDE).get().isLive()
    }
}
