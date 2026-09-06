package com.moltenbits.sideband.pending

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/** Everything a role has to look at is derived from the journal; the session adds only the read position. */
class JournalPendingSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Pending pending = context.getBean(Pending)
    Path dir = Files.createTempDirectory("pending")
    Path file = dir.resolve(Journal.FILE_NAME)

    Entry human(String body = "@codex review this", Role via = Role.CLAUDE, Role to = Role.CODEX) {
        journal.append(file, Fixtures.humanDraft(body, [ParticipantId.of(to)], via))
    }

    Entry agent(Role from, Role to, MessageType type, Map more = [:]) {
        journal.append(file, Fixtures.agentDraft([from: ParticipantId.of(from), to: [ParticipantId.of(to)], type: type,
                causedBy: null, replyTo: null, expectsReply: type == MessageType.REQUEST, body: type.id()] + more))
    }

    void "an unanswered request to the role is open, an acknowledged one is in progress, a replied one is gone"() {
        given:
        Entry first = human("@codex one")
        Entry second = human("@codex two")
        Entry third = human("@codex three")
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: second.metadata().id(), to: [Fixtures.OPERATOR]])
        agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: third.metadata().id(), to: [Fixtures.OPERATOR]])

        when:
        PendingReport report = pending.report(dir, Role.CODEX)

        then:
        report.open()*.entry()*.metadata()*.id() == [first.metadata().id()]
        report.open()[0].acknowledgedAt() == null
        report.inProgress()*.entry()*.metadata()*.id() == [second.metadata().id()]
        report.inProgress()[0].acknowledgedAt() != null
        report.updates().isEmpty()
        report.intent() == "Sideband delivery; use the Sideband skill (\$sideband) for handling instructions"
        report.end() == Files.size(file)
    }

    void "before any session everything predates it; after activation only later entries do not"() {
        given:
        Entry before = human("@codex before")

        expect:
        pending.report(dir, Role.CODEX).open()*.beforeSession() == [true]

        when:
        sessions.join(dir, Role.CODEX, "s1")
        Entry after = human("@codex after")

        then:
        pending.report(dir, Role.CODEX).open()*.beforeSession() == [true, false]
        pending.report(dir, Role.CODEX).session().id() == "s1"

        when: "a resumed session with several requests waiting still confirms them"
        sessions.join(dir, Role.CODEX, "s2", true)

        then:
        pending.report(dir, Role.CODEX).open()*.beforeSession() == [true, true]

        when: "one of them is answered, leaving a lone request: acted on without asking"
        agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: before.metadata().id(), to: [Fixtures.OPERATOR]])

        then:
        pending.report(dir, Role.CODEX).open()*.beforeSession() == [false]
    }

    void "after --resume an acknowledged request still counts toward the several-requests rule"() {
        given:
        Entry one = human("@codex one")
        Entry two = human("@codex two")
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: one.metadata().id(), to: [Fixtures.OPERATOR]])
        sessions.join(dir, Role.CODEX, "s1", true)

        expect:
        pending.report(dir, Role.CODEX).inProgress()*.beforeSession() == [true]
        pending.report(dir, Role.CODEX).open()*.beforeSession() == [true]
    }

    void "informational entries are updates until the read position passes them"() {
        given:
        sessions.join(dir, Role.CLAUDE, "s1")
        Entry status = agent(Role.CODEX, Role.CLAUDE, MessageType.STATUS)

        expect:
        pending.report(dir, Role.CLAUDE).updates()*.metadata()*.id() == [status.metadata().id()]

        when:
        sessions.advance(dir, Role.CLAUDE, status.end())

        then:
        pending.report(dir, Role.CLAUDE).updates().isEmpty()
    }

    void "a reply addressed to the operator alone does not close an agent's request; one addressed to the agent does"() {
        given:
        Entry h = human("@codex ask claude", Role.CODEX, Role.CODEX)
        Entry ask = agent(Role.CODEX, Role.CLAUDE, MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CLAUDE, Role.CODEX, MessageType.ACK, [replyTo: ask.metadata().id()])

        when: "Claude reports the result to the operator only"
        agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: ask.metadata().id(), to: [Fixtures.OPERATOR]])

        then: "Codex is still waiting, and Claude still has it in progress"
        pending.report(dir, Role.CODEX).outgoing()*.id() == [ask.metadata().id()]
        pending.report(dir, Role.CLAUDE).inProgress()*.entry()*.metadata()*.id() == [ask.metadata().id()]

        when: "Claude replies to Codex, copying the operator"
        agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: ask.metadata().id(), to: [Fixtures.CODEX, Fixtures.OPERATOR]])

        then:
        pending.report(dir, Role.CODEX).outgoing().isEmpty()
        pending.report(dir, Role.CLAUDE).inProgress().isEmpty()
    }

    void "the role's own human turn, entries it authored, and acks are never listed"() {
        given:
        Entry typedIntoCodex = human("typed into codex", Role.CODEX, Role.CODEX)
        Entry request = human("@codex go")
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: request.metadata().id()])
        agent(Role.CODEX, Role.CLAUDE, MessageType.STATUS)

        expect:
        pending.report(dir, Role.CODEX).open().isEmpty()
        pending.report(dir, Role.CODEX).inProgress()*.entry()*.metadata()*.id() == [request.metadata().id()]
        pending.report(dir, Role.CODEX).updates().isEmpty()
        pending.report(dir, Role.CLAUDE).updates()*.metadata()*.type() == [MessageType.STATUS]
    }

    void "outgoing requests report the recipient's acks and silence, and disappear once replied, even through a clarification"() {
        given:
        Entry h = human("@claude ask codex", Role.CLAUDE, Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, Role.CODEX, MessageType.REQUEST, [causedBy: h.metadata().id()])

        expect: "unacknowledged"
        with(pending.report(dir, Role.CLAUDE).outgoing()) {
            size() == 1
            it[0].id() == ask.metadata().id()
            it[0].acknowledgedAt() == null
            it[0].ackIds() == []
        }

        when:
        Entry ack = agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: ask.metadata().id()])
        Thread.sleep(1100)
        OutgoingReport report = pending.report(dir, Role.CLAUDE).outgoing()[0]

        then: "the silence is measured from the latest ack"
        report.acknowledgedAt() != null
        report.ackIds() == [ack.metadata().id()]
        report.silenceSeconds() >= 1

        when: "Codex asks a clarification as an actionable reply"
        Entry clarify = agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: ask.metadata().id(), expectsReply: true])

        then: "a question is not an answer: Claude's request stays outgoing and Codex's stays in progress"
        pending.report(dir, Role.CLAUDE).outgoing()*.id() == [ask.metadata().id()]
        pending.report(dir, Role.CODEX).inProgress()*.entry()*.metadata()*.id() == [ask.metadata().id()]
        pending.report(dir, Role.CLAUDE).open()*.entry()*.metadata()*.id() == [clarify.metadata().id()]

        when: "Claude answers the clarification and Codex replies to the answer"
        Entry answer = agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: clarify.metadata().id()])
        agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: answer.metadata().id()])

        then:
        pending.report(dir, Role.CLAUDE).outgoing().isEmpty()
        pending.report(dir, Role.CODEX).outgoing().isEmpty()
        pending.report(dir, Role.CODEX).inProgress().isEmpty()
    }

}
