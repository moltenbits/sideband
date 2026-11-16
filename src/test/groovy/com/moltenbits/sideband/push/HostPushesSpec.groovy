package com.moltenbits.sideband.push

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.handoff.Batch
import com.moltenbits.sideband.handoff.Handoffs
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Deliveries
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Runs the real push component against a fake `codex` executable that records its arguments. */
class HostPushesSpec extends Specification {

    @Shared Path fakeBin = Files.createTempDirectory("fake-codex")
    @Shared Path log = fakeBin.resolve("calls.log")
    @Shared Path exitFile = fakeBin.resolve("exit-code")
    /** An empty Claude registry and a home with no inbound setting, so no real session on the developer's machine is ever pushed to. */
    @Shared Path claudeRegistry = Files.createTempDirectory("claude-sessions")
    @Shared Path claudeHome = Files.createTempDirectory("claude-home")
    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run(
            ["sideband.codex.executable": fakeBin.resolve("codex").toString(),
             "sideband.claude.sessions-directory": claudeRegistry.toString(),
             "sideband.home-directory": claudeHome.toString()])

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Deliveries deliveries = context.getBean(Deliveries)
    Pushes pushes = context.getBean(Pushes)
    Path repo = TempRepo.init()
    Path state = Files.createDirectories(repo.resolve(".git/sideband"))

    def setupSpec() {
        Path script = fakeBin.resolve("codex")
        Files.writeString(script, '''#!/bin/sh
printf '%s\\n' "$@" >> "''' + log + '''"
printf 'Queued message fake for thread %s.\\n' "$3"
exit $(cat "''' + exitFile + '''")
''')
        Files.setPosixFilePermissions(script, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
    }

    def setup() {
        Files.writeString(exitFile, "0")
        Files.deleteIfExists(log)
    }

    Entry toCodex(String body = "@codex hello") {
        journal.append(state, Fixtures.humanDraft(body, [Fixtures.CODEX]))
    }

    void "the component is exposed only through its interface"() {
        expect:
        pushes instanceof HostPushes
        context.getBeansOfType(HostPusher)*.role() as Set == [Role.CODEX, Role.CLAUDE] as Set
    }

    void "without a Codex session the entry is left for backlog and codex is never run"() {
        when:
        List<PushResult> results = pushes.deliver(state, toCodex())

        then:
        results == [new PushResult(ParticipantId.of("repo", "codex"), PushOutcome.NO_SESSION, null)]
        !Files.exists(log)
    }

    void "with a live Codex session the envelope is queued to its thread"() {
        given:
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-123")
        Entry entry = toCodex("@codex please look")

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.outcome() == [PushOutcome.PUSHED]
        results[0].detail().contains("thread-123")
        List<String> argv = Files.readAllLines(log)
        argv[0..3] == ["queue", "--thread", "thread-123", "--message"]
        String message = argv[4..-1].join("\n")
        message.startsWith("[Sideband message]\n{")
        message.contains('"body":"@codex please look"')
        message.contains('"effective_live":"auto"')
        message.contains('"intent":"Sideband delivery; use the Sideband skill ($sideband) for handling instructions"')
        !message.contains("mark-delivered")
    }

    void "a pusher is handed the entry's author, so the host can attribute the message"() {
        given: "a Codex reply addressed to Claude, and a Claude pusher that records what it is given"
        Entry request = toCodex("@codex please look")
        Entry reply = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE],
                type: com.moltenbits.sideband.protocol.MessageType.REPLY, replyTo: request.metadata().id(),
                causedBy: null, expectsReply: false, body: "looked"))
        sessions.join(state, Fixtures.CLAUDE, Role.CLAUDE, "claude-1")
        List<ParticipantId> authors = []
        HostPusher recorder = [role: { Role.CLAUDE },
                               push: { Path directory, ParticipantId recipient, ParticipantId from, String text ->
                                   authors << from
                                   new PushResult(ParticipantId.of("repo", "claude"), PushOutcome.PUSHED, text)
                               }] as HostPusher
        Pushes wired = new HostPushes(context.getBean(Handoffs), deliveries, sessions, [recorder])

        when:
        List<PushResult> results = wired.deliver(state, reply)

        then:
        authors == [Fixtures.CODEX]
        results*.outcome() == [PushOutcome.PUSHED]
        results[0].detail().contains('"body":"looked"')
    }

    void "the pusher is the one of the client the recipient's record names, whatever its name"() {
        given: "opus is a name, and Codex joined under it"
        ParticipantId opus = ParticipantId.of("repo", "opus")
        sessions.join(state, opus, Role.CODEX, "thread-opus")
        Entry entry = journal.append(state, Fixtures.humanDraft("@opus look", [opus]))

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.outcome() == [PushOutcome.PUSHED]
        Files.readAllLines(log)[0..2] == ["queue", "--thread", "thread-opus"]
    }

    void "a name nobody has joined as gets nothing pushed, whatever it is called"() {
        when:
        List<PushResult> results = pushes.deliver(state, journal.append(state, Fixtures.humanDraft("@codex hi", [Fixtures.CODEX], Fixtures.CLAUDE)))

        then:
        results == [new PushResult(Fixtures.CODEX, PushOutcome.NO_SESSION, null)]
        !Files.exists(log)
    }

    void "an ack is never pushed; it waits for the requester's next look at its outgoing requests"() {
        given:
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-123")
        Entry request = toCodex("@codex please look")
        Entry ack = journal.append(state, Fixtures.agentDraft(from: Fixtures.CLAUDE, to: [Fixtures.CODEX],
                type: com.moltenbits.sideband.protocol.MessageType.ACK, replyTo: request.metadata().id(),
                causedBy: null, expectsReply: false, body: "received"))

        expect:
        pushes.deliver(state, ack).isEmpty()
        !Files.exists(log)
    }

    void "a failing codex queue leaves the entry pending with the reason, and records no delivery"() {
        given:
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-123")
        Files.writeString(exitFile, "3")
        Entry entry = toCodex()

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.outcome() == [PushOutcome.FAILED]
        results[0].detail().contains("exited 3")
        deliveries.pushedInto(state, ParticipantId.of("repo", "codex"), "thread-123").isEmpty()
    }

    void "a push the host accepted is recorded against the session it went into, so pending can say it is on its way"() {
        given:
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-123")
        Entry entry = toCodex("@codex please look")

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.session() == ["thread-123"]
        deliveries.pushedInto(state, ParticipantId.of("repo", "codex"), "thread-123").keySet() == [entry.seq()] as Set
        deliveries.pushedInto(state, ParticipantId.of("repo", "codex"), "thread-456").isEmpty()
        !Files.readAllLines(log).join("\n").contains("pushed_at")
    }

    void "the delivery is recorded against the destination the pusher used, not the role's record afterwards, which a join may move mid-push"() {
        given: "Codex holds thread A; while the push to A is in flight, Codex rejoins from thread B"
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-A")
        Entry entry = toCodex("@codex please look")
        HostPusher relocating = [role: { Role.CODEX },
                                 push: { Path directory, ParticipantId recipient, ParticipantId from, String text ->
                                     String target = sessions.load(directory, ParticipantId.of("repo", "codex")).get().id()
                                     sessions.join(directory, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-B", null, true)
                                     new PushResult(ParticipantId.of("repo", "codex"), PushOutcome.PUSHED, "queued to " + target, target)
                                 }] as HostPusher
        Pushes wired = new HostPushes(context.getBean(Handoffs), deliveries, sessions, [relocating])

        when:
        wired.deliver(state, entry)

        then: "A, which holds the envelope, has the record; B, which will never see it, has none"
        deliveries.pushedInto(state, ParticipantId.of("repo", "codex"), "thread-A").keySet() == [entry.seq()] as Set
        deliveries.pushedInto(state, ParticipantId.of("repo", "codex"), "thread-B").isEmpty()
    }

    void "Claude taking the name over while a push to its Codex holder is prepared leaves the entry waiting, never queued to a Claude session id"() {
        given: "Codex holds codex when the pusher is chosen; Claude joins as codex before the destination is read"
        sessions.join(state, Fixtures.CODEX, Role.CODEX, "codex-thread")
        Entry entry = toCodex()
        Handoffs actual = context.getBean(Handoffs)
        Handoffs racing = Stub(Handoffs) {
            prepare(_, _) >> { Path directory, List<Entry> entries ->
                sessions.join(state, Fixtures.CODEX, Role.CLAUDE, "claude-session")
                actual.prepare(directory, entries)
            }
            envelope(_) >> { Batch batch -> actual.envelope(batch) }
        }
        Pushes wired = new HostPushes(racing, deliveries, sessions, [new CodexQueuePusher(fakeBin.resolve("codex").toString(), sessions)])

        when:
        List<PushResult> results = wired.deliver(state, entry)

        then:
        results*.outcome() == [PushOutcome.NO_SESSION]
        !Files.exists(log)
        deliveries.pushedInto(state, Fixtures.CODEX, "claude-session").isEmpty()
    }

    void "a Claude push lands in whichever session accepts it, and that is the session recorded"() {
        given: "the recorded Claude session is A, but the socket that accepts belongs to B; then no Claude has joined at all"
        Entry first = journal.append(state, Fixtures.humanDraft("@claude one", [Fixtures.CLAUDE], Fixtures.CODEX))
        Entry third = journal.append(state, Fixtures.humanDraft("@claude three", [Fixtures.CLAUDE], Fixtures.CODEX))
        String accepting = "claude-B"
        HostPusher socket = [role: { Role.CLAUDE },
                             push: { Path directory, ParticipantId recipient, ParticipantId from, String text ->
                                 new PushResult(ParticipantId.of("repo", "claude"), PushOutcome.PUSHED, "posted", accepting)
                             }] as HostPusher
        Pushes wired = new HostPushes(context.getBean(Handoffs), deliveries, sessions, [socket])
        sessions.join(state, ParticipantId.of("repo", "claude"), Role.CLAUDE, "claude-A")

        when:
        wired.deliver(state, first)

        then:
        deliveries.pushedInto(state, ParticipantId.of("repo", "claude"), "claude-B").keySet() == [first.seq()] as Set
        deliveries.pushedInto(state, ParticipantId.of("repo", "claude"), "claude-A").isEmpty()

        when: "nobody has joined as claude in another project: nothing is pushed, and the entry waits"
        Path unjoined = Files.createDirectories(TempRepo.init().resolve(".git/sideband"))
        Entry elsewhere = journal.append(unjoined, Fixtures.humanDraft("@claude two", [Fixtures.CLAUDE], Fixtures.CODEX))
        accepting = "claude-C"
        List<PushResult> waiting = wired.deliver(unjoined, elsewhere)

        then:
        waiting == [new PushResult(Fixtures.CLAUDE, PushOutcome.NO_SESSION, null)]
        deliveries.pushedInto(unjoined, ParticipantId.of("repo", "claude"), "claude-C").isEmpty()

        when: "a host that accepted the text but could not say which session took it"
        accepting = null
        wired.deliver(state, third)

        then: "nothing is recorded: pending then shows the entry plainly rather than claiming a place it cannot name"
        deliveries.pushedInto(state, ParticipantId.of("repo", "claude"), "claude-C").isEmpty()
        deliveries.pushedInto(state, ParticipantId.of("repo", "claude"), "claude-B").keySet() == [first.seq()] as Set
    }

    void "Claude is pushed to over its inbox socket; with no Claude Code session registered for the repository the entry waits"() {
        when:
        List<PushResult> results = pushes.deliver(state, journal.append(state, Fixtures.humanDraft("@claude hi", [Fixtures.CLAUDE], Fixtures.CODEX)))

        then:
        results == [new PushResult(ParticipantId.of("repo", "claude"), PushOutcome.NO_SESSION, null)]
    }

    void "an agent's own role and human recipients are never pushed to"() {
        given:
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-123")
        Entry own = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CODEX, Fixtures.OPERATOR],
                type: com.moltenbits.sideband.protocol.MessageType.STATUS, causedBy: null, expectsReply: false, body: "note to self"))
        Entry toHuman = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.OPERATOR],
                type: com.moltenbits.sideband.protocol.MessageType.STATUS, causedBy: null, expectsReply: false, body: "done"))

        expect:
        pushes.deliver(state, own).isEmpty()
        pushes.deliver(state, toHuman).isEmpty()
        !Files.exists(log)
    }

    void "a broadcast pushes to each client recipient except the one the human typed into"() {
        given:
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-123")
        Entry viaClaude = journal.append(state, Fixtures.humanDraft("@all go", [Fixtures.CLAUDE, Fixtures.CODEX], Fixtures.CLAUDE))
        Entry viaCodex = journal.append(state, Fixtures.humanDraft("@all go", [Fixtures.CLAUDE, Fixtures.CODEX], Fixtures.CODEX))

        expect:
        pushes.deliver(state, viaClaude) == [new PushResult(ParticipantId.of("repo", "codex"), PushOutcome.PUSHED, "Queued message fake for thread thread-123.", "thread-123")]
        pushes.deliver(state, viaCodex) == [new PushResult(ParticipantId.of("repo", "claude"), PushOutcome.NO_SESSION, null)]
    }

    void "an entry for a named instance is queued into that instance's thread, and recorded against it"() {
        given:
        ParticipantId review = ParticipantId.of("repo", "review")
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-unnamed")
        sessions.join(state, review, Role.CODEX, "thread-review")
        Entry entry = journal.append(state, Fixtures.humanDraft("@codex:review look", [review]))

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.recipient() == [review]
        results*.outcome() == [PushOutcome.PUSHED]
        Files.readAllLines(log)[0..2] == ["queue", "--thread", "thread-review"]
        deliveries.pushedInto(state, review, "thread-review").keySet() == [entry.seq()] as Set
        deliveries.pushedInto(state, ParticipantId.of("repo", "codex"), "thread-unnamed").isEmpty()
    }

    void "a broadcast to two instances of one role pushes to each"() {
        given:
        ParticipantId review = ParticipantId.of("repo", "review")
        sessions.join(state, ParticipantId.of("repo", "codex"), Role.CODEX, "thread-unnamed")
        sessions.join(state, review, Role.CODEX, "thread-review")
        Entry entry = journal.append(state, Fixtures.humanDraft("@all look", [Fixtures.CODEX, review]))

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.recipient() == [Fixtures.CODEX, review]
        Files.readAllLines(log).findAll { it.startsWith("thread-") } == ["thread-unnamed", "thread-review"]
    }
}
