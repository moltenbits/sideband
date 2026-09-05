package com.moltenbits.sideband.recipient

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class FileRecipientStateSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    RecipientState state = context.getBean(RecipientState)
    Path dir = Files.createTempDirectory("recipient")
    Path file = dir.resolve(Journal.FILE_NAME)

    Entry human(String body = "@codex review this", Role via = Role.CLAUDE, Role to = Role.CODEX) {
        journal.append(file, Fixtures.humanDraft(body, [ParticipantId.of(to)], via))
    }

    Entry request(Role from, Role to, String causedBy) {
        journal.append(file, Fixtures.agentDraft(from: ParticipantId.of(from),
                to: [ParticipantId.of(to)], causedBy: causedBy))
    }

    Entry reply(Role from, Role to, String replyTo, boolean actionable = false) {
        journal.append(file, Fixtures.agentDraft(from: ParticipantId.of(from),
                to: [ParticipantId.of(to)], type: MessageType.REPLY,
                replyTo: replyTo, causedBy: null, expectsReply: actionable, body: "reply"))
    }

    void "the state is exposed only through its interface"() {
        expect:
        state instanceof FileRecipientState
    }

    void "an absent cursor loads as empty"() {
        expect:
        state.load(dir, Role.CODEX) == Cursor.empty(Role.CODEX)
        !Files.exists(dir.resolve("cursors/codex.json"))
    }

    void "activation on an empty journal records the session with no watermark entry"() {
        when:
        Activation activation = state.activate(dir, Role.CODEX, "s1", null, false)

        then:
        activation.session().id() == "s1"
        activation.session().watermarkId() == null
        activation.session().watermarkEnd() == 0
        activation.backlog().isEmpty()
        state.load(dir, Role.CODEX).session() == activation.session()
    }

    void "activation sets the watermark at the last complete entry and returns addressed unresolved entries as backlog"() {
        given:
        Entry toCodex = human()
        Entry toClaude = human("@claude look", Role.CODEX, Role.CLAUDE)
        Entry fromCodex = request(Role.CODEX, Role.CLAUDE, toClaude.metadata().id())

        when:
        Activation activation = state.activate(dir, Role.CODEX, "s1", null, false)

        then:
        activation.session().watermarkId() == fromCodex.metadata().id()
        activation.session().watermarkEnd() == Files.size(file)
        activation.backlog()*.metadata()*.id() == [toCodex.metadata().id()]
        state.load(dir, Role.CODEX).stateOf(toCodex.metadata().id()).seenAt() != null
        state.load(dir, Role.CODEX).stateOf(toClaude.metadata().id()) == EntryState.NONE
    }

    void "the cursor file is private JSON with snake_case fields"() {
        given:
        human()
        state.activate(dir, Role.CODEX, "s1", 4242L, false)

        when:
        Map json = context.getBean(ObjectMapper).readValue(Files.readString(dir.resolve("cursors/codex.json")), Map)

        then:
        json.schema == 1
        json.role == "codex"
        json.session.id == "s1"
        json.session.parent_pid == 4242
        json.session.watermark_end == Files.size(file)
        json.entries.size() == 1
        json.entries.values()[0].keySet() == ["seen_at", "delivered_at", "resolved_at", "resolution"] as Set
        json.outgoing == [:]
    }

    void "a second live session for the role is refused unless replaced"() {
        given:
        state.activate(dir, Role.CODEX, "s1", ProcessHandle.current().pid(), false)

        when:
        state.activate(dir, Role.CODEX, "s2", null, false)

        then:
        SessionConflictException e = thrown()
        e.existing().id() == "s1"

        when:
        Activation replaced = state.activate(dir, Role.CODEX, "s2", null, true)

        then:
        replaced.session().id() == "s2"
    }

    void "a session whose host process is dead is superseded automatically"() {
        given:
        state.activate(dir, Role.CODEX, "s1", 999999999L, false)

        expect:
        state.activate(dir, Role.CODEX, "s2", null, false).session().id() == "s2"
    }

    void "re-activating the same session is not a conflict"() {
        given:
        state.activate(dir, Role.CODEX, "s1", ProcessHandle.current().pid(), false)

        expect:
        state.activate(dir, Role.CODEX, "s1", null, false).session().id() == "s1"
    }

    void "refreshing a resumed process preserves the complete cursor except its process id"() {
        given:
        Entry backlog = human("before activation")
        state.activate(dir, Role.CODEX, "s1", 999999999L, false)
        Entry live = human("after activation")
        state.markDelivered(dir, Role.CODEX, [live.metadata().id()])
        state.registerOutgoing(dir, Role.CODEX, "pending-request")
        Cursor before = state.load(dir, Role.CODEX)
        String journalBefore = Files.readString(file)
        long currentPid = ProcessHandle.current().pid()

        when:
        def result = state.refreshSession(dir, Role.CODEX, "s1", currentPid)
        Cursor after = state.load(dir, Role.CODEX)

        then:
        result.name() == "REFRESHED"
        after == before.withSession(new Session("s1", before.session().startedAt(), currentPid,
                before.session().watermarkId(), before.session().watermarkEnd()))
        Files.readString(file) == journalBefore
        state.pending(dir, Role.CODEX).backlog()*.metadata()*.id() == [backlog.metadata().id()]
        state.pending(dir, Role.CODEX).live()*.metadata()*.id() == [live.metadata().id()]

        when:
        def again = state.refreshSession(dir, Role.CODEX, "s1", currentPid)

        then:
        again.name() == "READY"
        state.load(dir, Role.CODEX) == after
    }

    void "refresh cannot activate a role, replace another conversation, overwrite a live process, or revive with an unknown caller"() {
        given:
        Long oldPid = oldLive ? ProcessHandle.current().pid() : 999999999L
        if (active) state.activate(dir, Role.CODEX, "s1", oldPid, false)
        Cursor before = state.load(dir, Role.CODEX)
        Long pid = callerLive ? ProcessHandle.current().pid() : suppliedPid

        expect:
        state.refreshSession(dir, Role.CODEX, sessionId, pid).name() == outcome
        state.load(dir, Role.CODEX) == before

        where:
        active | oldLive | sessionId | callerLive | suppliedPid | outcome
        false  | false   | "s1"      | true       | null        | "NOT_ACTIVE"
        true   | false   | "other"   | true       | null        | "SESSION_MISMATCH"
        true   | true    | "other"   | true       | null        | "SESSION_MISMATCH"
        true   | false   | "s1"      | false      | null        | "CALLER_UNAVAILABLE"
        true   | false   | "s1"      | false      | 999999998L  | "CALLER_UNAVAILABLE"
        true   | true    | "s1"      | false      | 999999998L  | "READY"
    }

    void "resolved entries drop out of backlog and pending; dismissal never touches the journal"() {
        given:
        Entry first = human("first")
        Entry second = human("second")
        long size = Files.size(file)
        state.activate(dir, Role.CODEX, "s1", null, false)

        when:
        state.resolve(dir, Role.CODEX, [first.metadata().id()], Resolution.DISMISSED)

        then:
        state.pending(dir, Role.CODEX).backlog()*.metadata()*.id() == [second.metadata().id()]
        state.activate(dir, Role.CODEX, "s1", null, false).backlog()*.metadata()*.id() == [second.metadata().id()]
        state.load(dir, Role.CODEX).stateOf(first.metadata().id()).resolution() == Resolution.DISMISSED
        Files.size(file) == size
    }

    void "before any activation every open entry is backlog because no listener exists"() {
        given:
        Entry entry = human()

        expect:
        state.pending(dir, Role.CODEX).backlog()*.metadata()*.id() == [entry.metadata().id()]
        state.pending(dir, Role.CODEX).live().isEmpty()
    }

    void "entries after the watermark are live, not backlog"() {
        given:
        Entry before = human("before")
        state.activate(dir, Role.CODEX, "s1", null, false)
        Entry after = human("after")

        when:
        Pending pending = state.pending(dir, Role.CODEX)

        then:
        pending.backlog()*.metadata()*.id() == [before.metadata().id()]
        pending.live()*.metadata()*.id() == [after.metadata().id()]
    }

    void "seen, delivered, and resolved are separate facts"() {
        given:
        state.activate(dir, Role.CODEX, "s1", null, false)
        Entry entry = human()
        String id = entry.metadata().id()

        when:
        state.markSeen(dir, Role.CODEX, [id])

        then:
        with(state.load(dir, Role.CODEX).stateOf(id)) {
            seenAt != null
            deliveredAt == null
            resolvedAt == null
        }

        when:
        state.markDelivered(dir, Role.CODEX, [id])

        then:
        with(state.load(dir, Role.CODEX).stateOf(id)) {
            deliveredAt != null
            resolvedAt == null
        }
        state.pending(dir, Role.CODEX).live()*.metadata()*.id() == [id]

        when:
        state.resolve(dir, Role.CODEX, [id], Resolution.ACTED)

        then:
        state.load(dir, Role.CODEX).stateOf(id).resolution() == Resolution.ACTED
        state.pending(dir, Role.CODEX).live().isEmpty()
    }

    void "an entry authored by the role or not addressed to it is never open"() {
        given:
        Entry toClaude = human("@claude x", Role.CODEX, Role.CLAUDE)
        Entry fromCodex = request(Role.CODEX, Role.CLAUDE, toClaude.metadata().id())
        Cursor cursor = state.load(dir, Role.CODEX)

        expect:
        !state.isOpen(cursor, toClaude)
        !state.isOpen(cursor, fromCodex)
        state.isOpen(state.load(dir, Role.CLAUDE), fromCodex)
    }

    void "a human turn typed into the role is never open for it, even before the originating-turn resolution lands"() {
        given:
        Entry ownTurn = human("fix the typo", Role.CODEX, Role.CODEX)
        Entry broadcastFromCodex = journal.append(file, Fixtures.humanDraft("@all go", [Fixtures.CLAUDE, Fixtures.CODEX], Role.CODEX))

        expect:
        !state.isOpen(state.load(dir, Role.CODEX), ownTurn)
        !state.isOpen(state.load(dir, Role.CODEX), broadcastFromCodex)
        state.isOpen(state.load(dir, Role.CLAUDE), broadcastFromCodex)
    }

    void "outgoing requests are registered, correlated with replies through reply_to chains, and resolved by the parent"() {
        given:
        Entry h = human("@claude do it", Role.CLAUDE)
        Entry ask = request(Role.CLAUDE, Role.CODEX, h.metadata().id())
        state.registerOutgoing(dir, Role.CLAUDE, ask.metadata().id())
        Entry clarify = reply(Role.CODEX, Role.CLAUDE, ask.metadata().id(), true)
        Entry answerToClarify = reply(Role.CLAUDE, Role.CODEX, clarify.metadata().id())
        Entry finalReply = reply(Role.CODEX, Role.CLAUDE, answerToClarify.metadata().id())

        when:
        state.markDelivered(dir, Role.CLAUDE, [clarify.metadata().id(), finalReply.metadata().id()])
        OutgoingState outgoing = state.load(dir, Role.CLAUDE).outgoing()[ask.metadata().id()]

        then:
        outgoing.state() == OutgoingStatus.PENDING
        outgoing.replyIds() == [clarify.metadata().id(), finalReply.metadata().id()]
        state.pending(dir, Role.CLAUDE).outgoing().keySet() == [ask.metadata().id()] as Set

        when:
        state.resolveOutgoing(dir, Role.CLAUDE, [ask.metadata().id()], OutgoingStatus.ANSWERED)

        then:
        state.load(dir, Role.CLAUDE).outgoing()[ask.metadata().id()].state() == OutgoingStatus.ANSWERED
        state.pending(dir, Role.CLAUDE).outgoing().isEmpty()
    }

    void "activation reconciles outgoing requests the cursor never recorded, without disturbing resolved ones"() {
        given:
        Entry h = human("@claude do it", Role.CLAUDE)
        Entry ask1 = request(Role.CLAUDE, Role.CODEX, h.metadata().id())
        Entry ask2 = request(Role.CLAUDE, Role.CODEX, h.metadata().id())
        state.registerOutgoing(dir, Role.CLAUDE, ask1.metadata().id())
        state.resolveOutgoing(dir, Role.CLAUDE, [ask1.metadata().id()], OutgoingStatus.DISMISSED)
        reply(Role.CODEX, Role.CLAUDE, ask2.metadata().id())

        when:
        state.activate(dir, Role.CLAUDE, "s1", null, false)
        Cursor cursor = state.load(dir, Role.CLAUDE)

        then:
        cursor.outgoing()[ask1.metadata().id()].state() == OutgoingStatus.DISMISSED
        cursor.outgoing()[ask2.metadata().id()].state() == OutgoingStatus.PENDING
        cursor.outgoing()[ask2.metadata().id()].replyIds().size() == 1
    }

    void "resolving an unknown outgoing request is invalid input"() {
        when:
        state.resolveOutgoing(dir, Role.CLAUDE, ["ghost"], OutgoingStatus.ANSWERED)

        then:
        thrown(IllegalArgumentException)
    }

    void "a cursor with an unsupported schema is refused rather than misread"() {
        given:
        Files.createDirectories(dir.resolve("cursors"))
        Files.writeString(dir.resolve("cursors/codex.json"), '{"schema":2,"role":"codex","session":null,"entries":{},"outgoing":{}}')

        when:
        state.load(dir, Role.CODEX)

        then:
        thrown(IllegalStateException)
    }
}
