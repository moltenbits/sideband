package com.moltenbits.sideband.session

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class FileSessionsSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Path dir = Files.createTempDirectory("sessions")
    Path file = dir.resolve(Journal.FILE_NAME)

    void "the component is exposed only through its interface"() {
        expect:
        sessions instanceof FileSessions
    }

    void "no session until activation, and activation sets watermark and offset at the journal end"() {
        given:
        journal.append(file, Fixtures.humanDraft("@codex hi", [Fixtures.CODEX]))
        long end = Files.size(file)

        expect:
        sessions.load(dir, Role.CODEX).isEmpty()

        when:
        Session session = sessions.join(dir, Role.CODEX, "s1")

        then:
        session.id() == "s1"
        session.watermark() == end
        session.offset() == end
        sessions.load(dir, Role.CODEX).get() == session
        Files.readString(dir.resolve("sessions/codex.json")).contains('"watermark":' + end)
    }

    void "whoever joins last holds the role: a second join replaces the record, never refuses"() {
        given:
        sessions.join(dir, Role.CODEX, "s1")

        expect:
        sessions.join(dir, Role.CODEX, "s2").id() == "s2"
        sessions.load(dir, Role.CODEX).get().id() == "s2"
        sessions.join(dir, Role.CODEX, "s2").id() == "s2"
    }

    void "a record written when sessions still carried a process id loads without it"() {
        given:
        Files.createDirectories(dir.resolve("sessions"))
        Files.writeString(dir.resolve("sessions/claude.json"),
                '{"id":"old","started_at":"2026-09-06T09:38:08-05:00","parent_pid":66503,"watermark":8898,"offset":203657,"resumed":true}')

        expect:
        with(sessions.load(dir, Role.CLAUDE).get()) {
            id() == "old"
            watermark() == 8898
            offset() == 203657
            resumed()
        }
    }

    void "join starts at the latest point unless resuming, which keeps the previous bookmark"() {
        given:
        journal.append(file, Fixtures.humanDraft("@codex one", [Fixtures.CODEX]))
        long first = Files.size(file)

        expect: "a first join with --resume starts at the beginning of the journal"
        sessions.join(dir, Role.CODEX, "s1", true).offset() == 0

        when:
        sessions.advance(dir, Role.CODEX, first)
        journal.append(file, Fixtures.humanDraft("@codex two", [Fixtures.CODEX]))
        long second = Files.size(file)
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
}
