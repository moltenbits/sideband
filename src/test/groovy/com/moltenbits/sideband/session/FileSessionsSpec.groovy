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
        Session session = sessions.activate(dir, Role.CODEX, "s1", ProcessHandle.current().pid(), false)

        then:
        session.id() == "s1"
        session.watermark() == end
        session.offset() == end
        session.isLive()
        sessions.load(dir, Role.CODEX).get() == session
        Files.readString(dir.resolve("sessions/codex.json")).contains('"watermark":' + end)
    }

    void "a second live session for the role is refused unless replaced; a dead or identical one is not"() {
        given:
        sessions.activate(dir, Role.CODEX, "s1", ProcessHandle.current().pid(), false)

        when:
        sessions.activate(dir, Role.CODEX, "s2", null, false)

        then:
        thrown(SessionConflictException)

        expect:
        sessions.activate(dir, Role.CODEX, "s1", null, false).id() == "s1"
        sessions.activate(dir, Role.CODEX, "s2", null, true).id() == "s2"

        when: "the recorded process is dead"
        sessions.activate(dir, Role.CODEX, "s3", 999999999L, true)

        then:
        sessions.activate(dir, Role.CODEX, "s4", null, false).id() == "s4"
    }

    void "advance moves the read position forward only"() {
        given:
        sessions.activate(dir, Role.CLAUDE, "s1", null, false)

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

    void "refresh outcomes"() {
        given:
        Long me = ProcessHandle.current().pid()
        Long oldPid = oldLive ? me : 999999999L
        if (active) sessions.activate(dir, Role.CODEX, "s1", oldPid, false)
        Long caller = callerLive ? me : suppliedPid

        expect:
        sessions.refresh(dir, Role.CODEX, sessionId, caller).name() == outcome

        where:
        active | oldLive | sessionId | callerLive | suppliedPid | outcome
        false  | false   | "s1"      | true       | null        | "NOT_ACTIVE"
        true   | false   | "other"   | true       | null        | "SESSION_MISMATCH"
        true   | false   | "s1"      | false      | null        | "CALLER_UNAVAILABLE"
        true   | false   | "s1"      | false      | 999999998L  | "CALLER_UNAVAILABLE"
        true   | true    | "s1"      | false      | 999999998L  | "READY"
        true   | false   | "s1"      | true       | null        | "REFRESHED"
        true   | true    | "other"   | true       | null        | "REFRESHED"
    }

    void "refresh keeps everything but what it refreshes"() {
        given:
        Long me = ProcessHandle.current().pid()
        journal.append(file, Fixtures.humanDraft("@codex hi", [Fixtures.CODEX]))
        Session before = sessions.activate(dir, Role.CODEX, "s1", me, false)
        sessions.advance(dir, Role.CODEX, before.offset() + 5)

        when: "the same process shows up with a new conversation id"
        sessions.refresh(dir, Role.CODEX, "s2", me)
        Session after = sessions.load(dir, Role.CODEX).get()

        then:
        after.id() == "s2"
        after.parentPid() == me
        after.startedAt() == before.startedAt()
        after.watermark() == before.watermark()
        after.offset() == before.offset() + 5

        and: "a different process with a new id is still another conversation"
        sessions.refresh(dir, Role.CODEX, "elsewhere", 999999998L) == SessionRefresh.SESSION_MISMATCH
    }
}
