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

    void "the hook journals a prompt for the joined Claude role and tells the model so"() {
        given:
        detectedAgent = Role.CLAUDE
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

    void "the hook's note names the entry so a delegation can cite it without reading the journal"() {
        given:
        detectedAgent = Role.CLAUDE
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        when:
        hook("get codex to review the locking")
        String note = json().hookSpecificOutput.additionalContext
        String id = (note =~ /recorded this prompt as ([0-9a-f-]{36})/)[0][1]
        stdout = new StringWriter()
        int code = run("append", "--repo", repo.toString(), "--from", "claude", "--to", "codex", "--type", "request",
                "--caused-by", id, "--body-file", Files.writeString(repo.resolve("ask.md"), "review the locking").toString())

        then:
        note.contains("cite it as --caused-by")
        code == ExitCode.OK
        json().metadata.caused_by == id
        context.getBean(com.moltenbits.sideband.journal.Journal).readCompleteFrom(journalFile, 0).entries()*.metadata()*.from()*.toString() == ["operator", "claude"]
    }

    void "the hook stays silent and writes nothing when the prompt is not a human message, is the host's own notification, or Sideband is not set up here"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook(prompt, session, plainDirectory ? TempRepo.plainDirectory().toString() : repo.toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        prompt                                                        | session | plainDirectory
        "[Sideband message]\n{...}"                                   | "s1"    | false
        "<cross-session-message from-name=\"Codex\">\n[Sideband message]\n{...}\n</cross-session-message>" | "s1" | false
        "/sideband status"                                            | "s1"    | false
        "! sideband doctor"                                           | "s1"    | false
        "   "                                                         | "s1"    | false
        "<task-notification>\n<task-id>b1</task-id>\n</task-notification>" | "s1" | false
        "<system-reminder>\n[SYSTEM NOTIFICATION - NOT USER INPUT]"   | "s1"    | false
        "[SYSTEM NOTIFICATION - NOT USER INPUT]\nThis is automated"   | "s1"    | false
        "hello"                                                       | "s1"    | true
    }

    List<String> bodies() {
        Files.exists(journalFile) ? context.getBean(com.moltenbits.sideband.journal.Journal).readCompleteFrom(journalFile, 0).entries()*.body() : []
    }

    void "a message typed as the skill's argument is the operator's words: the hook records the text after the invocation"() {
        given:
        detectedAgent = Role.CLAUDE
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        when:
        int code = hook(prompt)

        then:
        code == ExitCode.OK
        stdout.toString().isEmpty() == (expected == null)
        bodies() == (expected == null ? [] : [expected])
        expected == null || json().hookSpecificOutput.additionalContext.startsWith("Sideband recorded this prompt as")

        where:
        prompt                                  | expected
        "/sideband @codex look at this"         | "@codex look at this"
        "  \$sideband  tell codex to wait  "     | "tell codex to wait"
        "/sideband"                             | null
        "/sideband pending"                     | null
        "/sideband OFF"                         | null
        "\$sideband"                            | null
        "  \$sideband  "                        | null
        "\$sideband help"                       | null
        "\$sideband Status"                     | null
        "\$sideband PENDING"                    | null
        "\$sideband off"                        | null
        "/sidebandish something"                | null
        "\$sidebandish something"               | "\$sidebandish something"
    }

    void "the hook never fails the prompt, even on garbage input"() {
        given:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("not json".bytes)

        expect:
        run("hook", "prompt") == ExitCode.OK
        stderr.toString().contains("unreadable payload")
        json().hookSpecificOutput.additionalContext.startsWith("Sideband could not confirm recording this prompt")

        cleanup:
        System.in = original
    }

    void "the same hook captures Codex prompts verbatim and never redelivers them to the originating turn"() {
        given:
        detectedAgent = Role.CODEX
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "codex-session")
        stdout = new StringWriter()
        String prompt = "  Please check café\nwith trailing spaces  "

        when:
        int code = hook(prompt, "codex-session")

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.hookEventName == "UserPromptSubmit"
        json().hookSpecificOutput.additionalContext.contains("Do not capture")

        when: "the journal holds the verbatim entry, addressed to the client it was typed into"
        def entries = context.getBean(com.moltenbits.sideband.journal.Journal).readCompleteFrom(journalFile, 0).entries()

        then:
        entries.size() == 1
        entries[0].body() == prompt
        entries[0].metadata().via() == Role.CODEX
        entries[0].metadata().from().toString() == "operator"

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

    void "shell markers and the registered agent both preserve the correct author and route, and the agent wins"() {
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
        run("pending", "--repo", repo.toString(), "--role", peer)

        then:
        json().open.size() == 1
        json().open[0].entry.metadata.via == owner
        json().open[0].entry.metadata.from == "operator"
        json().open[0].entry.metadata.to == [peer]
        json().open[0].entry.body == "@${peer} hello\n"

        where:
        detected    | owner    | peer     | flag
        Role.CODEX  | "codex"  | "claude" | null
        Role.CLAUDE | "claude" | "codex"  | null
        null        | "codex"  | "claude" | "codex"
        null        | "claude" | "codex"  | "claude"
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
        Files.readString(home.resolve(".agents/skills/sideband/SKILL.md")).endsWith(Files.readString(Path.of("skills/codex/INSTRUCTIONS.md")))

        when: "ejecting again is refused with a pointer to --force, and --force overwrites"
        stdout = new StringWriter()
        int refused = run("skill", "--client", "codex", "--home", home.toString(), "--eject")
        int forced = run("skill", "--client", "codex", "--home", home.toString(), "--eject", "--force")

        then:
        refused == ExitCode.INVALID_INPUT
        stderr.toString().contains("--force")
        forced == ExitCode.OK
        run("skill", "--client", "codex", "--home", home.toString(), "--force") == ExitCode.INVALID_INPUT
    }

    void "skill prints the embedded instructions for the named client as plain text"() {
        when:
        int code = run("skill", "--client", "codex")

        then:
        code == ExitCode.OK
        stdout.toString() == Files.readString(Path.of("skills/codex/INSTRUCTIONS.md"))
    }

    void "the role is all that matters: a restarted, cleared, or second client captures for the joined role, and the record follows the human's conversation"() {
        given:
        detectedAgent = detected
        run("join", "--repo", repo.toString(), "--role", owner, "--session-id", "s1")
        Sessions state = context.getBean(Sessions)
        Path dir = context.getBean(SidebandHome).locate(repo)
        def before = state.load(dir, Role.valueOf(owner.toUpperCase())).get()
        stdout = new StringWriter()

        when:
        int code = hook("first prompt after restart", session, repo.toString(), flag ? ["--agent", flag] : [])
        def after = state.load(dir, Role.valueOf(owner.toUpperCase())).get()

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.additionalContext.startsWith("Sideband recorded this prompt")
        Files.readString(journalFile).contains("first prompt after restart")
        after.id() == (session ?: "s1")
        after.startedAt() == before.startedAt()
        after.watermark() == before.watermark()
        after.offset() == before.offset()
        stderr.toString().contains("now delivers to") == (session != null && session != "s1")

        where:
        detected    | owner    | flag     | session
        Role.CODEX  | "codex"  | null     | "s1"
        Role.CODEX  | "codex"  | null     | "after-restart"
        Role.CLAUDE | "codex"  | "codex"  | "after-restart"
        Role.CLAUDE | "claude" | null     | "after-clear"
        null        | "claude" | "claude" | "after-clear"
        Role.CLAUDE | "claude" | null     | null
    }

    void "a prompt the hook does not record still moves the record to the conversation it was typed in, but a delivered envelope never does"() {
        given:
        detectedAgent = Role.CODEX
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "old-thread")
        Sessions state = context.getBean(Sessions)
        Path dir = context.getBean(SidebandHome).locate(repo)
        stdout = new StringWriter()

        when:
        int code = hook(prompt, "new-thread")

        then:
        code == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)
        state.load(dir, Role.CODEX).get().id() == expected

        where:
        prompt                                  | expected
        "/sideband status"                      | "new-thread"
        "\$sideband"                            | "new-thread"
        "! sideband doctor"                     | "new-thread"
        "   "                                   | "new-thread"
        "[Sideband message]\n{...}"             | "old-thread"
        "<cross-session-message from-name=\"Claude\">\n[Sideband message]\n{...}\n</cross-session-message>" | "old-thread"
        "<task-notification>\n<task-id>b1</task-id>\n</task-notification>" | "old-thread"
    }

    int sessionStart(String source, String sessionId = "new-thread", String cwd = repo.toString(), List<String> args = [], String event = "SessionStart") {
        InputStream original = System.in
        Map payload = [cwd: cwd, source: source, hook_event_name: event]
        if (sessionId != null) payload.session_id = sessionId
        System.in = new ByteArrayInputStream(context.getBean(ObjectMapper).writeValueAsString(payload).bytes)
        try {
            HostEnvironment host = Stub() {
                role() >> Optional.ofNullable(detectedAgent)
            }
            def command = new HookCommand.SessionStart(context.getBean(SidebandHome), host,
                    context.getBean(Sessions), context.getBean(Pending), context.getBean(ObjectMapper))
            CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true)
            cli.out = new PrintWriter(stdout, true)
            cli.err = new PrintWriter(stderr, true)
            return cli.execute(args as String[])
        } finally {
            System.in = original
        }
    }

    void "a cleared client moves the joined role to its new conversation before the first prompt and tells the model Sideband is live there"() {
        given:
        detectedAgent = detected
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "old-thread")
        Sessions state = context.getBean(Sessions)
        Path dir = context.getBean(SidebandHome).locate(repo)
        stdout = new StringWriter()

        when:
        int code = sessionStart("clear", "new-thread", repo.toString(), flag ? ["--agent", flag] : [])

        then:
        code == ExitCode.OK
        state.load(dir, Role.CODEX).get().id() == "new-thread"
        json().hookSpecificOutput.hookEventName == "SessionStart"
        json().hookSpecificOutput.additionalContext.contains("joined as Codex")
        json().hookSpecificOutput.additionalContext.contains("delivers to this conversation")
        json().hookSpecificOutput.additionalContext.contains("\$sideband")
        stderr.toString().contains("codex now delivers to new-thread")

        where:
        detected    | flag
        Role.CODEX  | null
        null        | "codex"
        Role.CLAUDE | "codex"
    }

    void "after a clear the model also hears how many entries addressed to it are waiting"() {
        given:
        detectedAgent = Role.CODEX
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "old-thread")
        context.getBean(com.moltenbits.sideband.journal.Journal).append(journalFile,
                com.moltenbits.sideband.Fixtures.agentDraft(from: com.moltenbits.sideband.Fixtures.CLAUDE,
                        to: [com.moltenbits.sideband.Fixtures.CODEX], type: com.moltenbits.sideband.protocol.MessageType.STATUS,
                        causedBy: null, expectsReply: false, body: "status"))
        stdout = new StringWriter()

        when:
        int code = sessionStart("clear")

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.additionalContext.contains("1 entry addressed to Codex is waiting")
    }

    void "a request acknowledged before the clear and still unanswered is counted too, so the new context picks it back up"() {
        given:
        detectedAgent = Role.CODEX
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "old-thread")
        def journal = context.getBean(com.moltenbits.sideband.journal.Journal)
        def request = journal.append(journalFile, com.moltenbits.sideband.Fixtures.agentDraft(body: "please review"))
        journal.append(journalFile, com.moltenbits.sideband.Fixtures.agentDraft(from: com.moltenbits.sideband.Fixtures.CODEX,
                to: [com.moltenbits.sideband.Fixtures.CLAUDE], type: com.moltenbits.sideband.protocol.MessageType.ACK,
                replyTo: request.metadata().id(), causedBy: null, expectsReply: false, body: "taking it up"))
        Sessions state = context.getBean(Sessions)
        Path dir = context.getBean(SidebandHome).locate(repo)
        long bookmark = state.load(dir, Role.CODEX).get().offset()
        stdout = new StringWriter()

        expect:
        context.getBean(Pending).report(dir, Role.CODEX).open().isEmpty()
        context.getBean(Pending).report(dir, Role.CODEX).inProgress().size() == 1

        when:
        int code = sessionStart("clear")

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.additionalContext.contains("1 entry addressed to Codex is waiting")
        state.load(dir, Role.CODEX).get().offset() == bookmark
    }

    void "a session start that is not a clear, is another event, names no session, or finds no joined role leaves the record alone and says nothing to the model"() {
        given:
        detectedAgent = Role.CODEX
        if (joined) run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "old-thread")
        Sessions state = context.getBean(Sessions)
        Path dir = context.getBean(SidebandHome).locate(repo)
        stdout = new StringWriter()

        when:
        int code = sessionStart(source, session, repo.toString(), [], event)

        then:
        code == ExitCode.OK
        stdout.toString().isEmpty()
        state.load(dir, Role.CODEX).map { it.id() }.orElse(null) == (joined ? "old-thread" : null)

        where:
        source    | session      | event              | joined
        "startup" | "new-thread" | "SessionStart"     | true
        "resume"  | "new-thread" | "SessionStart"     | true
        "compact" | "new-thread" | "SessionStart"     | true
        "clear"   | null         | "SessionStart"     | true
        "clear"   | "new-thread" | "UserPromptSubmit" | true
        "clear"   | "new-thread" | "SessionStart"     | false
    }

    void "the session-start hook never fails the host, even on garbage input or a client it cannot tell"() {
        given:
        detectedAgent = detected
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "old-thread")
        stdout = new StringWriter()
        InputStream original = System.in
        System.in = new ByteArrayInputStream(input.replace("REPO", repo.toString()).bytes)

        when:
        HostEnvironment host = Stub() { role() >> Optional.ofNullable(detectedAgent) }
        def command = new HookCommand.SessionStart(context.getBean(SidebandHome), host,
                context.getBean(Sessions), context.getBean(Pending), context.getBean(ObjectMapper))
        CommandLine cli = new CommandLine(command)
        cli.out = new PrintWriter(stdout, true)
        cli.err = new PrintWriter(stderr, true)
        int code = cli.execute()
        System.in = original

        then:
        code == ExitCode.OK
        stdout.toString().isEmpty()
        context.getBean(Sessions).load(context.getBean(SidebandHome).locate(repo), Role.CODEX).get().id() == "old-thread"

        where:
        detected   | input
        Role.CODEX | "not json"
        Role.CODEX | ""
        null       | '{"session_id":"new-thread","source":"clear","cwd":"REPO"}'
    }

    void "a prompt that should have been recorded and was not is reported to the model, never only to stderr"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()
        Files.createDirectories(journalFile)

        expect: "a directory where the journal belongs makes the append itself fail, so the outcome is uncertain"
        hook("this must not vanish") == ExitCode.OK
        json().hookSpecificOutput.hookEventName == "UserPromptSubmit"
        json().hookSpecificOutput.additionalContext.startsWith("Sideband could not confirm recording this prompt")
        json().hookSpecificOutput.additionalContext.endsWith("Tell the user.")
        stderr.toString().contains("capture failed")
    }

    void "a failure before the append says the prompt was not recorded"() {
        given:
        run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        captureOverride = { Path dir, Role via, String body ->
            throw new com.moltenbits.sideband.capture.CaptureFailedException(
                    com.moltenbits.sideband.capture.CaptureFailedException.Stage.NOT_JOURNALED, null, new RuntimeException("routing exploded"))
        } as HumanCapture
        stdout = new StringWriter()

        expect: "a failure before the append leaves nothing written"
        hook("lost before the journal") == ExitCode.OK
        json().hookSpecificOutput.additionalContext.startsWith("Sideband could not confirm recording this prompt")
        json().hookSpecificOutput.additionalContext.endsWith("Tell the user.")
        !json().hookSpecificOutput.additionalContext.contains("append")
        !Files.exists(journalFile)
    }

    void "a failure after the append reports the recorded id and the failed delivery"() {
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
        json().hookSpecificOutput.additionalContext.startsWith("Sideband recorded this prompt as " + id + " but could not deliver it")
        json().hookSpecificOutput.additionalContext.endsWith("Tell the user.")
        stderr.toString().contains("recorded " + id + " but could not deliver it")

        cleanup:
        Files.deleteIfExists(codexSession)
    }

    void "a caller whose client cannot be told is reported when any role is active, and silent otherwise"() {
        given:
        if (active) run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        if (both) run("join", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook("hello") == ExitCode.OK
        stderr.toString().contains("cannot tell which client")
        stderr.toString().contains("--agent")
        stdout.toString().isEmpty() == !active
        !active || json().hookSpecificOutput.additionalContext.contains("cannot tell which client")
        !Files.exists(journalFile)

        where:
        active | both
        false  | false
        true   | false
        true   | true
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

    void "an envelope is never captured, whatever the session record says"() {
        given:
        detectedAgent = Role.CODEX
        run("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")
        Path dir = context.getBean(SidebandHome).locate(repo)
        Sessions state = context.getBean(Sessions)
        def before = state.load(dir, Role.CODEX)
        stdout = new StringWriter()

        expect:
        hook("[Sideband message]\n{}", "other") == ExitCode.OK
        stdout.toString().isEmpty()
        stderr.toString().isEmpty()
        state.load(dir, Role.CODEX) == before
        !Files.exists(journalFile)
    }
}
