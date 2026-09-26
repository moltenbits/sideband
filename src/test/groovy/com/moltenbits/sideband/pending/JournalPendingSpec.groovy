package com.moltenbits.sideband.pending

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.handoff.Handoff
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
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

/** Everything a role has to look at is derived from the journal; the session adds only the read position. */
class JournalPendingSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Deliveries deliveries = context.getBean(Deliveries)
    Pending pending = context.getBean(Pending)
    Path dir = Files.createTempDirectory("pending")

    Entry human(String body = "@codex review this", Role via = Role.CLAUDE, Role to = Role.CODEX) {
        journal.append(dir, Fixtures.humanDraft(body, [ParticipantId.of(to)], ParticipantId.of(via)))
    }

    Entry agent(Role from, Role to, MessageType type, Map more = [:]) {
        journal.append(dir, Fixtures.agentDraft([from: ParticipantId.of(from), to: [ParticipantId.of(to)], type: type,
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
        PendingReport report = pending.report(dir, ParticipantId.of(Role.CODEX))

        then:
        report.open()*.entry()*.metadata()*.id() == [first.metadata().id()]
        report.open()[0].acknowledgedAt() == null
        report.inProgress()*.entry()*.metadata()*.id() == [second.metadata().id()]
        report.inProgress()[0].acknowledgedAt() != null
        report.updates().isEmpty()
        report.intent() == "Sideband delivery; use the Sideband skill (\$sideband) for handling instructions"
        report.end() == journal.end(dir)
    }

    void "before any session everything predates it; after activation only later entries do not"() {
        given:
        Entry before = human("@codex before")

        expect:
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.beforeSession() == [true]

        when:
        sessions.join(dir, ParticipantId.of(Role.CODEX), "s1")
        Entry after = human("@codex after")

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.beforeSession() == [true, false]
        pending.report(dir, ParticipantId.of(Role.CODEX)).session().id() == "s1"

        when: "a resumed session with several requests waiting still confirms them"
        sessions.join(dir, ParticipantId.of(Role.CODEX), "s2", null, true)

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.beforeSession() == [true, true]

        when: "one of them is answered, leaving a lone request: acted on without asking"
        agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: before.metadata().id(), to: [Fixtures.OPERATOR]])

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.beforeSession() == [false]
    }

    void "after --resume an acknowledged request still counts toward the several-requests rule"() {
        given:
        Entry one = human("@codex one")
        Entry two = human("@codex two")
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: one.metadata().id(), to: [Fixtures.OPERATOR]])
        sessions.join(dir, ParticipantId.of(Role.CODEX), "s1", null, true)

        expect:
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress()*.beforeSession() == [true]
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.beforeSession() == [true]
    }

    void "informational entries are updates until the read position passes them"() {
        given:
        sessions.join(dir, ParticipantId.of(Role.CLAUDE), "s1")
        Entry status = agent(Role.CODEX, Role.CLAUDE, MessageType.STATUS)

        expect:
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).updates()*.metadata()*.id() == [status.metadata().id()]

        when:
        sessions.advance(dir, ParticipantId.of(Role.CLAUDE), status.seq())

        then:
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).updates().isEmpty()
    }

    void "an update the writer pushed into this session says so, whether pending reads it before or after the envelope surfaces"() {
        given: "Codex is mid-turn: Claude's review was queued to its thread but not yet surfaced there"
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-1")
        Entry h = human("@codex implement it", Role.CODEX, Role.CODEX)
        Entry ask = agent(Role.CODEX, Role.CLAUDE, MessageType.REQUEST, [causedBy: h.metadata().id()])
        Entry review = agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: ask.metadata().id()])
        deliveries.record(dir, review.seq(), ParticipantId.of(Role.CODEX), "thread-1")

        when: "Codex reads pending inside the turn, before the envelope has surfaced"
        PendingReport before = pending.report(dir, ParticipantId.of(Role.CODEX))

        then: "the entry is listed, marked as already pushed into this very conversation"
        before.updates()*.metadata()*.id() == [review.metadata().id()]
        before.updates()[0].pushedAt() != null

        when: "the read position passes it, as a plain pending does; the envelope surfaces later on its own"
        sessions.advance(dir, ParticipantId.of(Role.CODEX), review.seq())

        then: "pending has nothing more to say about it; the journal state was never touched"
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates().isEmpty()

        when: "the reverse order: an entry that surfaced as an envelope first, then a pending read"
        Entry later = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)
        deliveries.record(dir, later.seq(), ParticipantId.of(Role.CODEX), "thread-1")
        PendingReport after = pending.report(dir, ParticipantId.of(Role.CODEX))

        then: "the same marker: the report cannot know the order, and the reader recognizes the id either way"
        after.updates()*.metadata()*.id() == [later.metadata().id()]
        after.updates()[0].pushedAt() != null
    }

    void "an entry whose push failed or was never attempted is unmarked: pending is the only path it reaches the role by"() {
        given:
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-1")
        Entry failed = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)

        expect:
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()*.metadata()*.id() == [failed.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()[0].pushedAt() == null
    }

    void "of several entries only the ones pushed into this session are marked"() {
        given:
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-1")
        Entry pushed = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)
        Entry unpushed = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)
        Entry pushedToo = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)
        deliveries.record(dir, pushed.seq(), ParticipantId.of(Role.CODEX), "thread-1")
        deliveries.record(dir, pushedToo.seq(), ParticipantId.of(Role.CODEX), "thread-1")

        when:
        List<Handoff> updates = pending.report(dir, ParticipantId.of(Role.CODEX)).updates()

        then:
        updates*.metadata()*.id() == [pushed, unpushed, pushedToo]*.metadata()*.id()
        updates*.pushedAt().collect { it != null } == [true, false, true]
    }

    void "a session that replaced the one pushed into has nothing in flight: after a restart or clear everything is shown plainly"() {
        given: "a review pushed into Codex's first thread, never read there"
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-1")
        Entry review = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)
        deliveries.record(dir, review.seq(), ParticipantId.of(Role.CODEX), "thread-1")

        when: "Codex restarts and joins from a new thread, resuming its read position"
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-2", null, true)

        then: "the entry is recovered through pending, with no claim that it is also on its way in"
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()*.metadata()*.id() == [review.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()[0].pushedAt() == null

        when: "the address moves without a join, as a clear does"
        Entry another = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)
        deliveries.record(dir, another.seq(), ParticipantId.of(Role.CODEX), "thread-2")
        sessions.follow(dir, Role.CODEX, "thread-3", null)

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()*.pushedAt() == [null, null]
    }

    void "a pushed request is a receipt, not a completion: it stays open until answered and in progress once acknowledged"() {
        given:
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-1")
        Entry request = human("@codex review this")
        deliveries.record(dir, request.seq(), ParticipantId.of(Role.CODEX), "thread-1")

        expect: "consumed through pending or not, the request is open and marked"
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.entry()*.metadata()*.id() == [request.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()[0].entry().pushedAt() != null

        when: "the read position passes it, as a plain pending does"
        sessions.advance(dir, ParticipantId.of(Role.CODEX), request.seq())

        then: "an unanswered request is not closed by having been shown"
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.entry()*.metadata()*.id() == [request.metadata().id()]

        when:
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: request.metadata().id(), to: [Fixtures.OPERATOR]])

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).open().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress()*.entry()*.pushedAt().every { it != null }

        when:
        agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: request.metadata().id(), to: [Fixtures.OPERATOR]])

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress().isEmpty()
    }

    void "a pending read that lands between the append and the host accepting the push sees the entry unmarked, and the next read marked"() {
        given:
        sessions.join(dir, ParticipantId.of(Role.CODEX), "thread-1")
        Entry status = agent(Role.CLAUDE, Role.CODEX, MessageType.STATUS)

        expect: "the entry is listed either way; the reader's rule that a repeated id is the same entry covers the gap"
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()[0].pushedAt() == null

        when:
        deliveries.record(dir, status.seq(), ParticipantId.of(Role.CODEX), "thread-1")

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()[0].pushedAt() != null
    }

    void "a reply addressed to the operator alone does not close an agent's request; one addressed to the agent does"() {
        given:
        Entry h = human("@codex ask claude", Role.CODEX, Role.CODEX)
        Entry ask = agent(Role.CODEX, Role.CLAUDE, MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CLAUDE, Role.CODEX, MessageType.ACK, [replyTo: ask.metadata().id()])

        when: "Claude reports the result to the operator only"
        agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: ask.metadata().id(), to: [Fixtures.OPERATOR]])

        then: "Codex is still waiting, and Claude still has it in progress"
        pending.report(dir, ParticipantId.of(Role.CODEX)).outgoing()*.id() == [ask.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).inProgress()*.entry()*.metadata()*.id() == [ask.metadata().id()]

        when: "Claude replies to Codex, copying the operator"
        agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: ask.metadata().id(), to: [Fixtures.CODEX, Fixtures.OPERATOR]])

        then:
        pending.report(dir, ParticipantId.of(Role.CODEX)).outgoing().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).inProgress().isEmpty()
    }

    void "the role's own human turn, entries it authored, and acks are never listed"() {
        given:
        Entry typedIntoCodex = human("typed into codex", Role.CODEX, Role.CODEX)
        Entry request = human("@codex go")
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: request.metadata().id()])
        agent(Role.CODEX, Role.CLAUDE, MessageType.STATUS)

        expect:
        pending.report(dir, ParticipantId.of(Role.CODEX)).open().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress()*.entry()*.metadata()*.id() == [request.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).updates()*.metadata()*.type() == [MessageType.STATUS]
    }

    void "outgoing requests report the recipient's acks and silence, and disappear once replied, even through a clarification"() {
        given:
        Entry h = human("@claude ask codex", Role.CLAUDE, Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, Role.CODEX, MessageType.REQUEST, [causedBy: h.metadata().id()])

        expect: "unacknowledged"
        with(pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing()) {
            size() == 1
            it[0].id() == ask.metadata().id()
            it[0].acknowledgedAt() == null
            it[0].ackIds() == []
        }

        when:
        Entry ack = agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: ask.metadata().id()])
        Thread.sleep(1100)
        OutgoingReport report = pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing()[0]

        then: "the silence is measured from the latest ack"
        report.acknowledgedAt() != null
        report.ackIds() == [ack.metadata().id()]
        report.silenceSeconds() >= 1

        when: "Codex asks a clarification as an actionable reply"
        Entry clarify = agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: ask.metadata().id(), expectsReply: true])

        then: "a question is not an answer: Claude's request stays outgoing and Codex's stays in progress"
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing()*.id() == [ask.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress()*.entry()*.metadata()*.id() == [ask.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).open()*.entry()*.metadata()*.id() == [clarify.metadata().id()]

        when: "Claude answers the clarification and Codex replies to the answer"
        Entry answer = agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: clarify.metadata().id()])
        agent(Role.CODEX, Role.CLAUDE, MessageType.REPLY, [replyTo: answer.metadata().id()])

        then:
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CODEX)).outgoing().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress().isEmpty()
    }

    void "an agent's later request to the same client supersedes its earlier one for both sides; the operator's prompts stack"() {
        given:
        Entry h = human("@claude ask codex", Role.CLAUDE, Role.CLAUDE)
        Entry first = agent(Role.CLAUDE, Role.CODEX, MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, Role.CLAUDE, MessageType.ACK, [replyTo: first.metadata().id()])

        expect:
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress()*.entry()*.metadata()*.id() == [first.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing()*.id() == [first.metadata().id()]

        when: "Claude asks Codex something else before Codex has answered"
        Entry second = agent(Role.CLAUDE, Role.CODEX, MessageType.REQUEST, [causedBy: h.metadata().id()])

        then: "the first is dismissed on both sides"
        pending.report(dir, ParticipantId.of(Role.CODEX)).inProgress().isEmpty()
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.entry()*.metadata()*.id() == [second.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing()*.id() == [second.metadata().id()]

        when: "a request that expects nothing back, a request to the other client, or a question in a reply supersedes nothing"
        agent(Role.CLAUDE, Role.CODEX, MessageType.REQUEST, [expectsReply: false])
        agent(Role.CLAUDE, Role.CODEX, MessageType.REPLY, [replyTo: second.metadata().id(), expectsReply: true, to: [Fixtures.OPERATOR]])
        human("@codex one", Role.CLAUDE, Role.CODEX)
        human("@codex two", Role.CLAUDE, Role.CODEX)

        then:
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).outgoing()*.id() == [second.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CODEX)).open()*.entry()*.body() == ["request", "@codex one", "@codex two"]
        pending.report(dir, ParticipantId.of(Role.CODEX)).updates()*.body() == ["request"]
    }

    void "each instance's report holds only what is addressed to it, and a prompt typed into an instance is never listed for it"() {
        given:
        ParticipantId fable = ParticipantId.of(Role.CLAUDE, "fable")
        sessions.join(dir, ParticipantId.of(Role.CLAUDE), "s1")
        sessions.join(dir, fable, "s2")
        Entry forFable = journal.append(dir, Fixtures.humanDraft("@claude:fable look", [fable], Fixtures.CODEX))
        Entry typedIntoFable = journal.append(dir, Fixtures.humanDraft("do it yourself", [fable], fable))
        Entry forClaude = journal.append(dir, Fixtures.humanDraft("@claude look", [Fixtures.CLAUDE], fable))

        expect:
        pending.report(dir, fable).open()*.entry()*.metadata()*.id() == [forFable.metadata().id()]
        pending.report(dir, ParticipantId.of(Role.CLAUDE)).open()*.entry()*.metadata()*.id() == [forClaude.metadata().id()]
        typedIntoFable.metadata().via() == fable
    }

    void "a request from one instance to another of the same role is outgoing for the one and open for the other"() {
        given:
        ParticipantId fable = ParticipantId.of(Role.CLAUDE, "fable")
        Entry prompt = journal.append(dir, Fixtures.humanDraft("ask fable to review", [Fixtures.CLAUDE], Fixtures.CLAUDE))
        Entry ask = journal.append(dir, Fixtures.agentDraft(from: Fixtures.CLAUDE, to: [fable], causedBy: prompt.metadata().id()))

        expect:
        pending.report(dir, Fixtures.CLAUDE).outgoing()*.id() == [ask.metadata().id()]
        pending.report(dir, fable).open()*.entry()*.metadata()*.id() == [ask.metadata().id()]

        when:
        journal.append(dir, Fixtures.agentDraft(from: fable, to: [Fixtures.CLAUDE], type: MessageType.REPLY,
                replyTo: ask.metadata().id(), causedBy: null, expectsReply: false, body: "reviewed"))

        then:
        pending.report(dir, Fixtures.CLAUDE).outgoing() == []
        pending.report(dir, fable).open() == []
    }
}
