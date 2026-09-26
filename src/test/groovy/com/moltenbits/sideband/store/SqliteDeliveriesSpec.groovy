package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Deliveries
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/** A push the host accepted is remembered against the session it went into, and nowhere else. */
class SqliteDeliveriesSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Deliveries deliveries = context.getBean(Deliveries)
    Path dir = Files.createTempDirectory("deliveries")

    void "the component is exposed only through its interface"() {
        expect:
        deliveries instanceof SqliteDeliveries
    }

    void "nothing is in flight before anything was pushed, even before the store exists"() {
        expect:
        deliveries.pushedInto(dir, Fixtures.CODEX, "thread-1").isEmpty()
    }

    void "a recorded push is in flight for the session it went into and no other"() {
        given:
        long first = journal.append(dir, Fixtures.humanDraft("@codex one", [Fixtures.CODEX])).seq()
        long second = journal.append(dir, Fixtures.humanDraft("@codex two", [Fixtures.CODEX])).seq()
        deliveries.record(dir, first, Fixtures.CODEX, "thread-1")
        deliveries.record(dir, second, Fixtures.CODEX, "thread-1")

        expect:
        deliveries.pushedInto(dir, Fixtures.CODEX, "thread-1").keySet() == [first, second] as Set
        deliveries.pushedInto(dir, Fixtures.CODEX, "thread-1").values().every { it != null }
        deliveries.pushedInto(dir, Fixtures.CODEX, "thread-2").isEmpty()
        deliveries.pushedInto(dir, Fixtures.CLAUDE, "thread-1").isEmpty()
    }

    void "each instance's deliveries are its own"() {
        given:
        ParticipantId fable = ParticipantId.of(Role.CLAUDE, "fable")
        long seq = journal.append(dir, Fixtures.humanDraft("@all one", [Fixtures.CLAUDE, fable])).seq()
        deliveries.record(dir, seq, fable, "s1")

        expect:
        deliveries.pushedInto(dir, fable, "s1").keySet() == [seq] as Set
        deliveries.pushedInto(dir, Fixtures.CLAUDE, "s1").isEmpty()

        when: "the same entry is pushed to the unnamed instance too"
        deliveries.record(dir, seq, Fixtures.CLAUDE, "s2")

        then: "neither record replaces the other"
        deliveries.pushedInto(dir, fable, "s1").keySet() == [seq] as Set
        deliveries.pushedInto(dir, Fixtures.CLAUDE, "s2").keySet() == [seq] as Set
    }

    void "pushing the same entry to the same role again replaces the record rather than adding one"() {
        given:
        long seq = journal.append(dir, Fixtures.humanDraft("@codex one", [Fixtures.CODEX])).seq()
        deliveries.record(dir, seq, Fixtures.CODEX, "thread-1")

        when:
        deliveries.record(dir, seq, Fixtures.CODEX, "thread-2")

        then:
        deliveries.pushedInto(dir, Fixtures.CODEX, "thread-1").isEmpty()
        deliveries.pushedInto(dir, Fixtures.CODEX, "thread-2").keySet() == [seq] as Set
    }
}
