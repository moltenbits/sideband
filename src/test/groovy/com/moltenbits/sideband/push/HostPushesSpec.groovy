package com.moltenbits.sideband.push

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Role
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
    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run(
            ["sideband.codex.executable": fakeBin.resolve("codex").toString()])

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Pushes pushes = context.getBean(Pushes)
    Path repo = TempRepo.init()
    Path state = Files.createDirectories(repo.resolve(".git/sideband"))
    Path file = state.resolve(Journal.FILE_NAME)

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
        journal.append(file, Fixtures.humanDraft(body, [Fixtures.CODEX]))
    }

    void "the component is exposed only through its interface"() {
        expect:
        pushes instanceof HostPushes
        context.getBeansOfType(HostPusher)*.role() == [Role.CODEX]
    }

    void "without a Codex session the entry is left for backlog and codex is never run"() {
        when:
        List<PushResult> results = pushes.deliver(state, toCodex())

        then:
        results == [new PushResult(Role.CODEX, PushOutcome.NO_SESSION, null)]
        !Files.exists(log)
    }

    void "with a live Codex session the envelope is queued to its thread"() {
        given:
        sessions.join(state, Role.CODEX, "thread-123", ProcessHandle.current().pid(), false)
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
        message.contains('"handling":"Sideband delivered these journal entries to Codex.')
        !message.contains("mark-delivered")
    }

    void "an ack is never pushed; it waits for the requester's next look at its outgoing requests"() {
        given:
        sessions.join(state, Role.CODEX, "thread-123", ProcessHandle.current().pid(), false)
        Entry request = toCodex("@codex please look")
        Entry ack = journal.append(file, Fixtures.agentDraft(from: Fixtures.CLAUDE, to: [Fixtures.CODEX],
                type: com.moltenbits.sideband.protocol.MessageType.ACK, replyTo: request.metadata().id(),
                causedBy: null, expectsReply: false, body: "received"))

        expect:
        pushes.deliver(state, ack).isEmpty()
        !Files.exists(log)
    }

    void "a dead Codex session is reported and nothing is queued"() {
        given:
        sessions.join(state, Role.CODEX, "thread-old", 999999999L, false)

        when:
        List<PushResult> results = pushes.deliver(state, toCodex())

        then:
        results*.outcome() == [PushOutcome.SESSION_DEAD]
        !Files.exists(log)
    }

    void "a failing codex queue leaves the entry pending with the reason"() {
        given:
        sessions.join(state, Role.CODEX, "thread-123", ProcessHandle.current().pid(), false)
        Files.writeString(exitFile, "3")
        Entry entry = toCodex()

        when:
        List<PushResult> results = pushes.deliver(state, entry)

        then:
        results*.outcome() == [PushOutcome.FAILED]
        results[0].detail().contains("exited 3")
    }

    void "Claude has no push command, so its own listener delivers"() {
        when:
        List<PushResult> results = pushes.deliver(state, journal.append(file, Fixtures.humanDraft("@claude hi", [Fixtures.CLAUDE], Role.CODEX)))

        then:
        results == [new PushResult(Role.CLAUDE, PushOutcome.LISTENER_DELIVERS, null)]
    }

    void "an agent's own role and human recipients are never pushed to"() {
        given:
        sessions.join(state, Role.CODEX, "thread-123", ProcessHandle.current().pid(), false)
        Entry own = journal.append(file, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CODEX, Fixtures.OPERATOR],
                type: com.moltenbits.sideband.protocol.MessageType.STATUS, causedBy: null, expectsReply: false, body: "note to self"))
        Entry toHuman = journal.append(file, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.OPERATOR],
                type: com.moltenbits.sideband.protocol.MessageType.STATUS, causedBy: null, expectsReply: false, body: "done"))

        expect:
        pushes.deliver(state, own).isEmpty()
        pushes.deliver(state, toHuman).isEmpty()
        !Files.exists(log)
    }

    void "a broadcast pushes to each client recipient except the one the human typed into"() {
        given:
        sessions.join(state, Role.CODEX, "thread-123", ProcessHandle.current().pid(), false)
        Entry viaClaude = journal.append(file, Fixtures.humanDraft("@all go", [Fixtures.CLAUDE, Fixtures.CODEX], Role.CLAUDE))
        Entry viaCodex = journal.append(file, Fixtures.humanDraft("@all go", [Fixtures.CLAUDE, Fixtures.CODEX], Role.CODEX))

        expect:
        pushes.deliver(state, viaClaude) == [new PushResult(Role.CODEX, PushOutcome.PUSHED, "Queued message fake for thread thread-123.")]
        pushes.deliver(state, viaCodex) == [new PushResult(Role.CLAUDE, PushOutcome.LISTENER_DELIVERS, null)]
    }
}
