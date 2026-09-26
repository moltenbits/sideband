package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.ancestry.Ancestry
import com.moltenbits.sideband.capture.HumanCapture
import com.moltenbits.sideband.home.SidebandHome
import com.moltenbits.sideband.host.HostEnvironment
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.pending.Pending
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.push.Pushes
import com.moltenbits.sideband.session.Sessions
import com.moltenbits.sideband.waiting.JournalWatcher
import io.micronaut.serde.ObjectMapper
import picocli.CommandLine

import java.nio.file.Files
import java.nio.file.Path

/** Joining as an instance, and who the model's own commands are (REQUIREMENTS.md 9.5a). */
class InstanceCommandsSpec extends CommandSpec {

    static final ParticipantId CLAUDE = Fixtures.CLAUDE
    static final ParticipantId FABLE = ParticipantId.of(Role.CLAUDE, "fable")

    Path repo = TempRepo.init()
    Path state = repo.resolve(".git/sideband")
    Sessions sessions = context.getBean(Sessions)
    Journal journal = context.getBean(Journal)

    Map runJson(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    void join(String session, String name = null) {
        runJson(["join", "--repo", repo.toString(), "--role", "claude", "--session-id", session]
                + (name ? ["--as", name] : []) as String[])
    }

    /** Runs a command the way the model does, from a Claude Code shell of this session, which names no process here. */
    int from(String session, Object command, String... args) {
        stdout = new StringWriter()
        stderr = new StringWriter()
        CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(ExitCode.HANDLER)
        cli.out = new PrintWriter(stdout, true)
        cli.err = new PrintWriter(stderr, true)
        cli.execute(["--repo", repo.toString()] + args.toList() as String[])
    }

    HostEnvironment shell(String session) {
        Stub(HostEnvironment) {
            role() >> Optional.of(Role.CLAUDE)
            sessionId(Role.CLAUDE) >> Optional.of(session)
            process(_) >> Optional.empty()
        }
    }

    PendingCommand pending(String session) {
        new PendingCommand(context.getBean(SidebandHome), shell(session), sessions, context.getBean(Pending),
                context.getBean(JournalWatcher), context.getBean(ObjectMapper))
    }

    AppendCommand append(String session) {
        new AppendCommand(context.getBean(SidebandHome), shell(session), journal, context.getBean(Ancestry),
                context.getBean(HumanCapture), context.getBean(Pushes), sessions, context.getBean(ObjectMapper))
    }

    Path body(String text) {
        Files.writeString(repo.resolve("body.md"), text)
    }

    // --- join ---

    void "join --as joins a named instance, beside the unnamed one"() {
        when:
        join("s1")
        Map joined = runJson("join", "--repo", repo.toString(), "--role", "claude", "--as", "fable", "--session-id", "s2")

        then:
        joined.session.id == "s2"
        !joined.containsKey("replaced")
        sessions.load(state, FABLE).get().id() == "s2"
        sessions.load(state, CLAUDE).get().id() == "s1"
    }

    void "a second join under a name takes it over, and says which session it replaced"() {
        given:
        join("s1", "fable")

        when:
        Map joined = runJson("join", "--repo", repo.toString(), "--role", "claude", "--as", "fable", "--session-id", "s2")

        then:
        joined.replaced == "s1"
        sessions.load(state, FABLE).get().id() == "s2"
    }

    void "a name that is not valid is refused, with the rule"() {
        expect:
        run("join", "--repo", repo.toString(), "--role", "claude", "--as", name, "--session-id", "s1") == ExitCode.INVALID_INPUT
        stderr.toString().contains("a lowercase letter followed by")
        sessions.all(state).isEmpty()

        where:
        name << ["Fable", "2nd", "fable:x", "a b"]
    }

    void "switching a conversation from the unnamed instance to a named one leaves the unnamed instance unjoined"() {
        given:
        join("s1")

        when:
        join("s1", "fable")

        then:
        sessions.load(state, CLAUDE).isEmpty()
        sessions.load(state, FABLE).get().id() == "s1"
    }

    // --- who a command is ---

    void "a command acts as the instance whose record names its session"() {
        given:
        join("s1")
        join("s2", "fable")

        when:
        int code = from("s2", pending("s2"))

        then:
        code == ExitCode.OK
        json().session.id == "s2"

        when: "it writes as that instance too"
        from("s2", append("s2"), "--type", "status", "--to", "codex", "--body-file", body("from fable").toString())

        then:
        journal.readAfter(state, 0).entries().last().metadata().from() == FABLE
    }

    void "a command from a session that holds no instance is refused and writes nothing, rather than acting as another instance"() {
        given: "fable was taken over by another session while this one was working"
        join("s1")
        join("s2", "fable")
        join("s3", "fable")
        long end = journal.end(state)

        expect:
        from("s2", pending("s2")) == ExitCode.INVALID_INPUT
        stderr.toString().contains("this Claude session holds no Sideband instance here: claude, claude:fable are joined from other sessions")
        stderr.toString().contains("/sideband joins as claude, and /sideband as <name> joins as claude:<name>")
        from("s2", append("s2"), "--type", "status", "--to", "codex", "--body-file", body("lost").toString()) == ExitCode.INVALID_INPUT
        journal.end(state) == end
        sessions.load(state, FABLE).get().offset() == end
    }

    void "a command acts as the unnamed instance when the role has no records at all, as before instances"() {
        when:
        from("s9", append("s9"), "--type", "status", "--to", "codex", "--body-file", body("never joined").toString())

        then:
        journal.readAfter(state, 0).entries().last().metadata().from() == CLAUDE
    }

    void "the operator's words appended from a named instance's session go through that instance"() {
        given:
        join("s2", "fable")

        when:
        from("s2", append("s2"), "--from", "operator", "--body-file", body("@codex look").toString())

        then:
        with(journal.readAfter(state, 0).entries().last().metadata()) {
            via() == FABLE
            to() == [Fixtures.CODEX]
        }
    }

    void "a reply goes back to the instance that asked"() {
        given:
        join("s2", "fable")
        String prompt = journal.append(state, Fixtures.humanDraft("@codex go", [Fixtures.CODEX], FABLE)).metadata().id()
        from("s2", append("s2"), "--type", "request", "--to", "codex", "--caused-by", prompt, "--body-file", body("review this").toString())
        String ask = json().metadata.id

        when:
        Map reply = runJson("append", "--repo", repo.toString(), "--from", "codex", "--type", "reply", "--reply-to", ask,
                "--body-file", body("done").toString())

        then:
        reply.metadata.to == ["claude:fable"]
    }
}
