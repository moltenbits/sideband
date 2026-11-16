package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Draft
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.CaptureLedger
import com.moltenbits.sideband.session.CaptureLedger.Cursor
import com.moltenbits.sideband.session.CaptureLedger.Mode
import com.moltenbits.sideband.session.CaptureLedger.State
import com.moltenbits.sideband.session.CaptureLedger.Turn
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SqliteCaptureLedgerSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    CaptureLedger ledger = context.getBean(CaptureLedger)
    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Path dir = Files.createTempDirectory("ledger")
    Path transcript = dir.resolve("t.jsonl")

    def setup() {
        sessions.join(dir, Fixtures.CLAUDE, Role.CLAUDE, "s1")
    }

    Draft said(String text) {
        Draft.capturedTurn(Fixtures.CLAUDE, null, text)
    }

    void "the component is exposed only through its interface"() {
        expect:
        ledger instanceof SqliteCaptureLedger
    }

    void "a session nothing was captured for has no state"() {
        expect:
        ledger.load(dir, "repo", Role.CLAUDE, "s1") == new State(0, null, [:])
        ledger.load(Files.createTempDirectory("absent"), "repo", Role.CLAUDE, "s1").version() == 0
    }

    void "a commit journals its drafts in order, keeps the cursor and turns, and advances the version"() {
        when:
        def entries = ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, ledger.load(dir, "repo", Role.CLAUDE, "s1"), Fixtures.CLAUDE,
                [said("first"), said("second")], ["u-1", "u-2"], new Cursor(transcript, 120, "p-1"),
                [new Turn("p-1", "prompt-1", Mode.TRANSCRIPT, true)])

        then:
        entries.get()*.body() == ["first", "second"]
        journal.readAfter(dir, 0).entries()*.body() == ["first", "second"]

        and: "the messages are kept as recorded, for this session only"
        ledger.recorded(dir, "repo", Role.CLAUDE, "s1", ["u-1", "u-2", "u-3"]) == ["u-1", "u-2"] as Set
        ledger.recorded(dir, "repo", Role.CLAUDE, "s2", ["u-1"]).isEmpty()
        ledger.recorded(dir, "repo", Role.CLAUDE, "s1", []).isEmpty()

        and:
        State state = ledger.load(dir, "repo", Role.CLAUDE, "s1")
        state.version() == 1
        state.cursor() == new Cursor(transcript, 120, "p-1")
        state.turn("p-1") == new Turn("p-1", "prompt-1", Mode.TRANSCRIPT, true)
        state.turn("p-2") == Turn.untouched("p-2")
    }

    void "the same host session, turn, and message ids in two projects keep two capture states"() {
        given:
        ParticipantId elsewhere = ParticipantId.of("listenly", "claude")
        sessions.join(dir, elsewhere, Role.CLAUDE, "s1")

        when:
        ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, ledger.load(dir, "repo", Role.CLAUDE, "s1"), Fixtures.CLAUDE,
                [said("in repo")], ["u-1"], new Cursor(transcript, 10, "p-1"), [new Turn("p-1", "prompt-1", Mode.TRANSCRIPT, true)])

        then: "the other project's state for the same ids is untouched"
        ledger.load(dir, "listenly", Role.CLAUDE, "s1") == new State(0, null, [:])
        ledger.recorded(dir, "listenly", Role.CLAUDE, "s1", ["u-1"]).isEmpty()

        when: "and it commits on its own version"
        def committed = ledger.commit(dir, "listenly", Role.CLAUDE, "s1", null, ledger.load(dir, "listenly", Role.CLAUDE, "s1"), elsewhere,
                [Draft.capturedTurn(elsewhere, null, "in listenly")], ["u-1"], new Cursor(transcript, 99, "p-1"),
                [new Turn("p-1", "prompt-9", Mode.TRANSCRIPT, false)])

        then:
        committed.isPresent()
        ledger.load(dir, "repo", Role.CLAUDE, "s1").cursor().position() == 10
        ledger.load(dir, "listenly", Role.CLAUDE, "s1").cursor().position() == 99
        ledger.load(dir, "listenly", Role.CLAUDE, "s1").turn("p-1").promptEntryId() == "prompt-9"
    }

    void "turns not named in a commit keep their receipts, and a named one is replaced"() {
        given:
        ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, ledger.load(dir, "repo", Role.CLAUDE, "s1"), null, [], [], null,
                [new Turn("p-1", "prompt-1", Mode.NONE, false), new Turn("p-2", "prompt-2", Mode.NONE, false)])

        when:
        ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, ledger.load(dir, "repo", Role.CLAUDE, "s1"), null, [], [], null,
                [new Turn("p-2", "prompt-2", Mode.PAYLOAD, true)])

        then:
        ledger.load(dir, "repo", Role.CLAUDE, "s1").turns() == [
                "p-1": new Turn("p-1", "prompt-1", Mode.NONE, false),
                "p-2": new Turn("p-2", "prompt-2", Mode.PAYLOAD, true)]
    }

    void "a commit from a state that has since changed writes nothing, even at an unchanged cursor"() {
        given: "two captures read the same state"
        State read = ledger.load(dir, "repo", Role.CLAUDE, "s1")

        when: "the first records a payload final without moving any cursor"
        def first = ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, read, Fixtures.CLAUDE, [said("Done.")], [], null,
                [new Turn("p-1", null, Mode.PAYLOAD, true)])

        and: "the second decided the same from the same read"
        def second = ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, read, Fixtures.CLAUDE, [said("Done.")], [], null,
                [new Turn("p-1", null, Mode.PAYLOAD, true)])

        then:
        first.present
        second.empty
        journal.readAfter(dir, 0).entries()*.body() == ["Done."]
        ledger.load(dir, "repo", Role.CLAUDE, "s1").version() == 1
    }

    void "a commit for an instance the session no longer is writes nothing"() {
        given:
        State read = ledger.load(dir, "repo", Role.CLAUDE, "s1")

        when: "another session takes the instance over between the read and the commit"
        sessions.join(dir, Fixtures.CLAUDE, Role.CLAUDE, "s2")
        def entries = ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, read, Fixtures.CLAUDE, [said("lost")], ["u-lost"],
                new Cursor(transcript, 10, "p-1"), [])

        then:
        entries.empty
        journal.end(dir) == 0
        ledger.load(dir, "repo", Role.CLAUDE, "s1").version() == 0
        ledger.recorded(dir, "repo", Role.CLAUDE, "s1", ["u-lost"]).isEmpty()
    }

    void "a commit names the exact instance: another instance of the same client is not enough"() {
        given:
        ParticipantId fable = ParticipantId.of("repo", "fable")
        State read = ledger.load(dir, "repo", Role.CLAUDE, "s1")

        expect:
        ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, read, fable, [said("as fable")], [], null, []).empty
        journal.end(dir) == 0
    }

    void "of many captures committing from one read, exactly one is written"() {
        given:
        State read = ledger.load(dir, "repo", Role.CLAUDE, "s1")
        CountDownLatch start = new CountDownLatch(1)
        def pool = Executors.newFixedThreadPool(8)

        when:
        def results = (1..8).collect { n ->
            pool.submit({
                start.await()
                ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, read, Fixtures.CLAUDE, [said("Done.")], ["u-done"],
                        new Cursor(transcript, 50, "p-1"), [new Turn("p-1", null, Mode.TRANSCRIPT, true)]).present
            } as Callable<Boolean>)
        }
        start.countDown()
        List<Boolean> committed = results*.get()
        pool.shutdown()

        then:
        committed.count { it } == 1
        journal.readAfter(dir, 0).entries()*.body() == ["Done."]
    }

    void "sessions and clients keep separate state"() {
        when:
        ledger.commit(dir, "repo", Role.CLAUDE, "s1", null, ledger.load(dir, "repo", Role.CLAUDE, "s1"), null, [], [],
                new Cursor(transcript, 5, "p-1"), [])

        then:
        ledger.load(dir, "repo", Role.CLAUDE, "s2").version() == 0
        ledger.load(dir, "repo", Role.CODEX, "s1").version() == 0
    }
}
