package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.host.HostProcess
import com.moltenbits.sideband.host.HostProcesses
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.InstanceRule
import com.moltenbits.sideband.session.Joining
import com.moltenbits.sideband.session.Session
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SqliteSessionsSpec extends Specification {

    static final ParticipantId CLAUDE = Fixtures.CLAUDE
    static final ParticipantId CODEX = Fixtures.CODEX
    static final ParticipantId FABLE = ParticipantId.of(Role.CLAUDE, "fable")

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Path dir = Files.createTempDirectory("sessions")

    /** This very JVM, which is certainly alive, and a process that is certainly not. */
    HostProcess self = context.getBean(HostProcesses).describe(ProcessHandle.current().pid()).get()
    HostProcess gone = new HostProcess(self.pid(), Instant.EPOCH)

    void "the component is exposed only through its interface"() {
        expect:
        sessions instanceof SqliteSessions
    }

    void "no session until activation, and activation sets watermark and offset at the journal end"() {
        given:
        long end = journal.append(dir, Fixtures.humanDraft("@codex hi", [CODEX])).seq()

        expect:
        sessions.load(dir, CODEX).isEmpty()

        when:
        Session session = sessions.join(dir, CODEX, "s1").session()

        then:
        session.id() == "s1"
        session.watermark() == end
        session.offset() == end
        !session.resumed()
        session.process() == null
        sessions.load(dir, CODEX).get() == session
        sessions.load(dir, CLAUDE).isEmpty()
    }

    void "a role can join before anything is written, and the store is created for it"() {
        when:
        Session session = sessions.join(dir, CLAUDE, "s1").session()

        then:
        session.watermark() == 0
        session.offset() == 0
        Files.exists(dir.resolve(Store.FILE_NAME))
        journal.end(dir) == 0
    }

    void "loading from a directory with no store is empty without creating one"() {
        expect:
        sessions.load(dir, CODEX).isEmpty()
        sessions.all(dir).isEmpty()
        !Files.exists(dir.resolve(Store.FILE_NAME))
    }

    void "whoever joins under an identifier last holds it, and the join names the session it replaced"() {
        given:
        sessions.join(dir, CODEX, "s1")

        when:
        Joining second = sessions.join(dir, CODEX, "s2")

        then:
        second.session().id() == "s2"
        second.replaced() == "s1"
        sessions.load(dir, CODEX).get().id() == "s2"

        and: "joining the same session again replaces nothing"
        sessions.join(dir, CODEX, "s2").replaced() == null
    }

    void "join starts at the latest point unless resuming, which keeps the instance's previous bookmark"() {
        given:
        long first = journal.append(dir, Fixtures.humanDraft("@codex one", [CODEX])).seq()

        expect: "a first join with --resume starts at the beginning of the journal"
        sessions.join(dir, CODEX, "s1", null, true).session().offset() == 0

        when:
        sessions.advance(dir, CODEX, first)
        long second = journal.append(dir, Fixtures.humanDraft("@codex two", [CODEX])).seq()
        Session plain = sessions.join(dir, CODEX, "s2").session()

        then: "a plain join starts at the latest point"
        plain.watermark() == second
        plain.offset() == second

        when:
        sessions.advance(dir, CODEX, first)   // no effect: never moves back
        Session resumed = sessions.join(dir, CODEX, "s3", null, true).session()

        then: "a resumed join keeps the bookmark and still marks the session start"
        resumed.watermark() == second
        resumed.offset() == second
        resumed.resumed()
    }

    void "advance moves the read position forward only"() {
        given:
        sessions.join(dir, FABLE, "s1")

        expect:
        sessions.advance(dir, FABLE, 40).offset() == 40
        sessions.advance(dir, FABLE, 10).offset() == 40
        sessions.load(dir, FABLE).get().offset() == 40
    }

    void "advancing without a session is an error"() {
        when:
        sessions.advance(dir, FABLE, 1)

        then:
        thrown(IllegalStateException)
    }

    void "each instance's record is independent, and records are listed by role"() {
        given:
        sessions.join(dir, CODEX, "c1")
        sessions.join(dir, CLAUDE, "k1", null, true)
        sessions.join(dir, FABLE, "f1")
        sessions.advance(dir, FABLE, 9)

        expect:
        sessions.load(dir, CODEX).get().id() == "c1"
        sessions.load(dir, CLAUDE).get().id() == "k1"
        sessions.load(dir, CLAUDE).get().resumed()
        sessions.load(dir, CLAUDE).get().offset() == 0
        sessions.load(dir, FABLE).get().offset() == 9
        sessions.records(dir, Role.CLAUDE).keySet() as List == [CLAUDE, FABLE]
        sessions.records(dir, Role.CODEX).keySet() as List == [CODEX]
        sessions.all(dir).keySet() as List == [CLAUDE, FABLE, CODEX]
    }

    void "a join records the host process, and a session holds one instance"() {
        given:
        sessions.join(dir, CLAUDE, "s1", self, false)

        when: "the same conversation joins again under a name"
        sessions.join(dir, FABLE, "s1", self, false)

        then:
        sessions.load(dir, FABLE).get().process() == self
        sessions.load(dir, CLAUDE).isEmpty()
    }

    void "a join takes its process from any other record naming it"() {
        given:
        sessions.join(dir, CLAUDE, "s1", self, false)

        when:
        sessions.join(dir, FABLE, "s2", self, false)

        then:
        sessions.load(dir, FABLE).get().process() == self
        sessions.load(dir, CLAUDE).get().process() == null
        sessions.load(dir, CLAUDE).get().id() == "s1"
    }

    void "following a clear moves the instance to the new conversation and keeps its bookmark"() {
        given:
        journal.append(dir, Fixtures.humanDraft("@claude hi", [CLAUDE]))
        Session joined = sessions.join(dir, FABLE, "old", self, true).session()
        sessions.advance(dir, FABLE, 7)
        sessions.join(dir, CLAUDE, "other", new HostProcess(1, Instant.EPOCH), false)

        when:
        InstanceRule.Followed followed = sessions.follow(dir, Role.CLAUDE, "new", self)

        then:
        followed.instance() == FABLE
        followed.moved()
        with(sessions.load(dir, FABLE).get()) {
            id() == "new"
            startedAt() == joined.startedAt()
            watermark() == joined.watermark()
            offset() == 7
            resumed()
            process() == self
        }
        sessions.load(dir, CLAUDE).get().id() == "other"
    }

    void "a restarted client continues the one instance whose recorded process is gone"() {
        given:
        sessions.join(dir, CLAUDE, "old", gone, false)

        when:
        InstanceRule.Followed followed = sessions.follow(dir, Role.CLAUDE, "new", self)

        then:
        followed.instance() == CLAUDE
        sessions.load(dir, CLAUDE).get().id() == "new"
        sessions.load(dir, CLAUDE).get().process() == self
    }

    void "a session no instance continues changes nothing"() {
        given:
        sessions.join(dir, CLAUDE, "s1", self, false)

        when:
        InstanceRule.Followed followed = sessions.follow(dir, Role.CLAUDE, "s2", new HostProcess(1, Instant.EPOCH))

        then:
        followed.instance() == null
        sessions.load(dir, CLAUDE).get().id() == "s1"
        sessions.load(dir, CLAUDE).get().process() == self
    }

    void "following in a directory with no store writes nothing"() {
        expect:
        sessions.follow(dir, Role.CLAUDE, "s1", self).instance() == null
        !Files.exists(dir.resolve(Store.FILE_NAME))
    }

    void "identifying a command reads the records and writes nothing"() {
        given:
        sessions.join(dir, FABLE, "s1", gone, false)

        expect:
        sessions.identify(dir, Role.CLAUDE, "s1", self).instance() == FABLE
        sessions.identify(dir, Role.CLAUDE, "s2", self).instance() == null
        sessions.identify(dir, Role.CODEX, "t1", null).instance() == CODEX
        sessions.load(dir, FABLE).get().process() == gone
    }
}
