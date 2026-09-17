package com.moltenbits.sideband.pending

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether the operator's attention is wanted when a role's turn ends is derived from the
 * journal alone: the operator's last prompt, the client it was typed into, and what has
 * been asked and answered since.
 */
class JournalAttentionSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Attention attention = context.getBean(Attention)
    Path dir = Files.createTempDirectory("attention")

    Entry human(String body = "do the thing", Role via = Role.CLAUDE, Role to = via) {
        journal.append(dir, Fixtures.humanDraft(body, [ParticipantId.of(to)], via))
    }

    Entry agent(Role from, List<ParticipantId> to, MessageType type, Map more = [:]) {
        journal.append(dir, Fixtures.agentDraft([from: ParticipantId.of(from), to: to, type: type,
                causedBy: null, replyTo: null, expectsReply: type == MessageType.REQUEST, body: type.id()] + more))
    }

    void "the component is exposed only through its interface"() {
        expect:
        context.getBean(Attention) instanceof JournalAttention
    }

    void "with nothing from the operator in the discussion there is nothing to hold back"() {
        expect:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        attention.atTurnEnd(dir, Role.CODEX).wanted()
    }

    void "the client the operator typed into is done when nothing is open, and the other client stays quiet"() {
        given:
        human("fix the build", Role.CLAUDE)

        expect:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
        attention.atTurnEnd(dir, Role.CODEX).reason().contains("typed into Claude")
    }

    void "a delegation keeps the operator's client quiet until the peer has answered and the client has had its turn"() {
        given: "the operator asks Claude, and Claude asks Codex for a review"
        Entry h = human("commit and get a review", Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])

        expect: "Claude's turn ends waiting; Codex's turn ends with the request taken up but unanswered"
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        attention.atTurnEnd(dir, Role.CLAUDE).reason().contains("1 open request")
        !attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "Codex acknowledges: still open"
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.ACK, [replyTo: ask.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()

        when: "Codex replies, copying the operator; the reply wakes Claude, so Codex's own turn end is not the moment"
        agent(Role.CODEX, [Fixtures.CLAUDE, Fixtures.OPERATOR], MessageType.REPLY, [replyTo: ask.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        attention.atTurnEnd(dir, Role.CLAUDE).reason() == "nothing is open"
    }

    void "a reply that only asks a question keeps the request open"() {
        given:
        Entry h = human("commit and get a review", Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REPLY, [replyTo: ask.metadata().id(), expectsReply: true])

        expect:
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "a request the operator addressed to the other client is open until that client answers"() {
        given: "typed into Claude, addressed to Codex"
        Entry h = human("@codex review the locking", Role.CLAUDE, Role.CODEX)

        expect: "Claude has nothing to do but is not done: Codex has not answered"
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        !attention.atTurnEnd(dir, Role.CODEX).wanted()

        when:
        agent(Role.CODEX, [Fixtures.CLAUDE, Fixtures.OPERATOR], MessageType.REPLY, [replyTo: h.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
    }

    void "a client that addresses the operator alone wants attention in its own terminal, whichever client the operator typed into"() {
        given:
        Entry h = human("commit and get a review", Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])

        when: "Codex asks the operator a question"
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.REQUEST, [causedBy: ask.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CODEX).wanted()
        attention.atTurnEnd(dir, Role.CODEX).reason().contains("went to the operator alone")
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()

        when: "and so does the operator's own client when it stops to ask, even with its request to Codex still open"
        agent(Role.CLAUDE, [Fixtures.OPERATOR], MessageType.REQUEST, [causedBy: h.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "a follow-up prompt while a delegation is open is part of the same task: the client stays quiet until the peer answers"() {
        given:
        Entry h = human("commit and get a review", Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])

        when: "the operator asks Claude how the review is going"
        human("how is the review going?", Role.CLAUDE)

        then:
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        attention.atTurnEnd(dir, Role.CLAUDE).reason().contains("1 open request")

        when:
        agent(Role.CODEX, [Fixtures.CLAUDE, Fixtures.OPERATOR], MessageType.REPLY, [replyTo: ask.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "an acknowledgement to the operator is a receipt, never a word to the operator"() {
        given: "typed into Claude, addressed to Codex; Codex acks the operator and delegates to Claude"
        Entry h = human("@codex review this and get Claude to check the tests", Role.CLAUDE, Role.CODEX)
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.ACK, [replyTo: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REQUEST, [causedBy: h.metadata().id()])

        expect:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "only the client's latest word counts: an earlier reply to the operator alone does not ring for later turns spent asking the peer"() {
        given:
        Entry h = human("get two reviews", Role.CLAUDE)
        Entry first = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REPLY, [replyTo: first.metadata().id()])
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.REPLY, [replyTo: first.metadata().id()])

        expect: "Codex's latest word went to the operator alone"
        attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "a second review, and Codex asks Claude something and stops"
        Entry second = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.ACK, [replyTo: second.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REQUEST, [causedBy: second.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "an agent's later request to the same client supersedes its earlier one, so an abandoned request never holds a notification back"() {
        given: "a review Codex acknowledged and never answered"
        Entry h = human("get a review", Role.CLAUDE)
        Entry stale = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.ACK, [replyTo: stale.metadata().id()])

        when: "Claude asks again, and Codex answers the new request"
        Entry again = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
        attention.atTurnEnd(dir, Role.CLAUDE).reason().contains("1 open request")

        when:
        agent(Role.CODEX, [Fixtures.CLAUDE, Fixtures.OPERATOR], MessageType.REPLY, [replyTo: again.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "an earlier prompt of the operator's that the other client never answered is not this task's business"() {
        given:
        human("@codex hi", Role.CLAUDE, Role.CODEX)

        when:
        human("fix the build", Role.CLAUDE)

        then:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "context arriving after a client's word to the operator does not ring again on the turn it starts"() {
        given: "Codex answered Claude and recorded its reply to the operator: that turn end rings"
        Entry h = human("get a review", Role.CLAUDE)
        Entry ask = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REPLY, [replyTo: ask.metadata().id()])
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.REPLY, [replyTo: ask.metadata().id()])

        expect:
        attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "Claude sends Codex a status, which Codex reads and answers with nothing"
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.STATUS)

        then:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "Codex speaks to the operator again"
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.STATUS)

        then:
        attention.atTurnEnd(dir, Role.CODEX).wanted()
    }

    void "a request that expects nothing back is context and supersedes no work"() {
        given:
        Entry h = human("get a review", Role.CLAUDE)
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id(), expectsReply: false])

        expect:
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "the operator's client rings once for done: on the turn that handled the completing reply, not on context that follows"() {
        given: "typed into Codex; Codex asks Claude, Claude answers, Codex reports to the operator"
        Entry h = human("get Claude to review", Role.CODEX)
        Entry ask = agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REPLY, [replyTo: ask.metadata().id()])

        expect: "the turn that handled Claude's reply"
        attention.atTurnEnd(dir, Role.CODEX).wanted()

        when:
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.REPLY, [replyTo: ask.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "Claude sends a status afterwards; Codex reads it and writes nothing"
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.STATUS)

        then:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
        attention.atTurnEnd(dir, Role.CODEX).reason().contains("context from Claude")
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()

        when: "Codex says something itself"
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.STATUS)

        then:
        attention.atTurnEnd(dir, Role.CODEX).wanted()
    }

    void "a reply to context, or a late reply to a request already closed, completes nothing and rings nothing"() {
        given: "typed into Codex; Codex delegates, Claude answers, Codex tells Claude and the operator"
        Entry h = human("get Claude to review", Role.CODEX)
        Entry ask = agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REPLY, [replyTo: ask.metadata().id()])
        Entry status = agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.STATUS)
        agent(Role.CODEX, [Fixtures.OPERATOR], MessageType.REPLY, [replyTo: ask.metadata().id()])

        expect:
        attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "Claude replies to the status"
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REPLY, [replyTo: status.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()

        when: "and then replies to the review request a second time"
        agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REPLY, [replyTo: ask.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CODEX).wanted()
    }

    void "a reply that closes a request superseded meanwhile completes nothing either"() {
        given:
        Entry h = human("get a review", Role.CLAUDE)
        Entry first = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        Entry second = agent(Role.CLAUDE, [Fixtures.CODEX], MessageType.REQUEST, [causedBy: h.metadata().id()])
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REPLY, [replyTo: second.metadata().id()])

        expect:
        attention.atTurnEnd(dir, Role.CLAUDE).wanted()

        when: "Codex answers the superseded one late"
        agent(Role.CODEX, [Fixtures.CLAUDE], MessageType.REPLY, [replyTo: first.metadata().id()])

        then:
        !attention.atTurnEnd(dir, Role.CLAUDE).wanted()
    }

    void "a broadcast is answered once per recipient: the second answer completes it, whoever answered first"() {
        given: "typed into Codex, addressed to both"
        Entry h = journal.append(dir, Fixtures.humanDraft("@all review independently", [Fixtures.CLAUDE, Fixtures.CODEX], Role.CODEX))
        agent(Role.CODEX, [Fixtures.OPERATOR, Fixtures.CLAUDE], MessageType.REPLY, [replyTo: h.metadata().id()])

        expect: "Claude has not answered yet"
        !attention.atTurnEnd(dir, Role.CODEX).wanted()

        when:
        agent(Role.CLAUDE, [Fixtures.OPERATOR, Fixtures.CODEX], MessageType.REPLY, [replyTo: h.metadata().id()])

        then:
        attention.atTurnEnd(dir, Role.CODEX).wanted()
    }
}
