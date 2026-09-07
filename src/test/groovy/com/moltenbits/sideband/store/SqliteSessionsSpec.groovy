package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Session
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class SqliteSessionsSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Path dir = Files.createTempDirectory("sessions")

    void "the component is exposed only through its interface"() {
        expect:
        sessions instanceof SqliteSessions
    }

    void "no session until activation, and activation sets watermark and offset at the journal end"() {
        given:
        long end = journal.append(dir, Fixtures.humanDraft("@codex hi", [Fixtures.CODEX])).seq()

        expect:
        sessions.load(dir, Role.CODEX).isEmpty()

        when:
        Session session = sessions.join(dir, Role.CODEX, "s1")

        then:
        session.id() == "s1"
        session.watermark() == end
        session.offset() == end
        !session.resumed()
        sessions.load(dir, Role.CODEX).get() == session
        sessions.load(dir, Role.CLAUDE).isEmpty()
    }

    void "a role can join before anything is written, and the store is created for it"() {
        when:
        Session session = sessions.join(dir, Role.CLAUDE, "s1")

        then:
        session.watermark() == 0
        session.offset() == 0
        Files.exists(dir.resolve(Store.FILE_NAME))
        journal.end(dir) == 0
    }

    void "loading from a directory with no store is empty without creating one"() {
        expect:
        sessions.load(dir, Role.CODEX).isEmpty()
        !Files.exists(dir.resolve(Store.FILE_NAME))
    }

    void "whoever joins last holds the role: a second join replaces the record, never refuses"() {
        given:
        sessions.join(dir, Role.CODEX, "s1")

        expect:
        sessions.join(dir, Role.CODEX, "s2").id() == "s2"
        sessions.load(dir, Role.CODEX).get().id() == "s2"
        sessions.join(dir, Role.CODEX, "s2").id() == "s2"
    }

    void "join starts at the latest point unless resuming, which keeps the previous bookmark"() {
        given:
        long first = journal.append(dir, Fixtures.humanDraft("@codex one", [Fixtures.CODEX])).seq()

        expect: "a first join with --resume starts at the beginning of the journal"
        sessions.join(dir, Role.CODEX, "s1", true).offset() == 0

        when:
        sessions.advance(dir, Role.CODEX, first)
        long second = journal.append(dir, Fixtures.humanDraft("@codex two", [Fixtures.CODEX])).seq()
        Session plain = sessions.join(dir, Role.CODEX, "s2", false)

        then: "a plain join starts at the latest point"
        plain.watermark() == second
        plain.offset() == second

        when:
        sessions.advance(dir, Role.CODEX, first)   // no effect: never moves back
        Session resumed = sessions.join(dir, Role.CODEX, "s3", true)

        then: "a resumed join keeps the bookmark and still marks the session start"
        resumed.watermark() == second
        resumed.offset() == second
        resumed.resumed()
    }

    void "advance moves the read position forward only"() {
        given:
        sessions.join(dir, Role.CLAUDE, "s1")

        expect:
        sessions.advance(dir, Role.CLAUDE, 40).offset() == 40
        sessions.advance(dir, Role.CLAUDE, 10).offset() == 40
        sessions.load(dir, Role.CLAUDE).get().offset() == 40
    }

    void "advancing without a session is an error"() {
        when:
        sessions.advance(dir, Role.CLAUDE, 1)

        then:
        thrown(IllegalStateException)
    }

    void "relocating moves the role to a new host session and keeps everything else, including the bookmark"() {
        given:
        journal.append(dir, Fixtures.humanDraft("@codex hi", [Fixtures.CODEX]))
        Session joined = sessions.join(dir, Role.CODEX, "old-thread", true)
        sessions.advance(dir, Role.CODEX, 7)

        when:
        Session moved = sessions.relocate(dir, Role.CODEX, "new-thread").get()

        then:
        moved.id() == "new-thread"
        moved.startedAt() == joined.startedAt()
        moved.watermark() == joined.watermark()
        moved.offset() == 7
        moved.resumed()
        sessions.load(dir, Role.CODEX).get() == moved
    }

    void "relocating to the session already recorded changes nothing"() {
        given:
        Session joined = sessions.join(dir, Role.CODEX, "s1")

        expect:
        sessions.relocate(dir, Role.CODEX, "s1").get() == joined
        sessions.load(dir, Role.CODEX).get() == joined
    }

    void "relocating a role that has not joined records nothing"() {
        expect:
        sessions.relocate(dir, Role.CLAUDE, "s9").isEmpty()
        sessions.load(dir, Role.CLAUDE).isEmpty()
    }

    void "each role's record is independent"() {
        given:
        sessions.join(dir, Role.CODEX, "c1")
        sessions.join(dir, Role.CLAUDE, "k1", true)

        expect:
        sessions.load(dir, Role.CODEX).get().id() == "c1"
        sessions.load(dir, Role.CLAUDE).get().id() == "k1"
        sessions.load(dir, Role.CLAUDE).get().resumed()
        !sessions.load(dir, Role.CODEX).get().resumed()
    }
}
