package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Draft
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Deliveries
import com.moltenbits.sideband.session.HeldPrompts
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import org.flywaydb.core.Flyway
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.function.Function

class SqliteHeldPromptsSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    HeldPrompts held = context.getBean(HeldPrompts)
    Path dir = Files.createTempDirectory("held")

    static Function<String, Draft> toClaude = { String prompt -> Fixtures.humanDraft(prompt, [Fixtures.CLAUDE]) }

    void "the component is exposed only through its interface"() {
        expect:
        held instanceof SqliteHeldPrompts
    }

    void "adopting removes the hold and journals the entry in one transaction"() {
        given:
        held.hold(dir, Role.CLAUDE, "s1", "review this")

        when:
        Optional<Entry> adopted = held.adopt(dir, Role.CLAUDE, "s1", toClaude)

        then:
        adopted.get().body() == "review this"
        adopted.get().seq() == 1
        journal.end(dir) == 1
        held.held(dir, Role.CLAUDE, "s1").isEmpty()
        held.adopt(dir, Role.CLAUDE, "s1", toClaude).isEmpty()
    }

    void "a failure while journaling keeps the hold, so a retried join can adopt it"() {
        given:
        held.hold(dir, Role.CLAUDE, "s1", "review this")

        when:
        held.adopt(dir, Role.CLAUDE, "s1", { String prompt -> throw new IllegalStateException("disk full") })

        then:
        thrown(IllegalStateException)
        journal.end(dir) == 0
        held.held(dir, Role.CLAUDE, "s1") == Optional.of("review this")
    }

    void "each session's hold is its own: holding, adopting, or dropping in one leaves the other"() {
        given: "two sessions of one role, neither joined, each with a prompt"
        held.hold(dir, Role.CLAUDE, "s1", "from s1")
        held.hold(dir, Role.CLAUDE, "s2", "from s2")

        expect: "the second hold replaced nothing"
        held.held(dir, Role.CLAUDE, "s1") == Optional.of("from s1")

        when: "s2 joins"
        Optional<Entry> adopted = held.adopt(dir, Role.CLAUDE, "s2", toClaude)

        then: "only its own prompt is journaled, and s1's hold waits for s1's join"
        adopted.get().body() == "from s2"
        journal.end(dir) == 1
        held.held(dir, Role.CLAUDE, "s1") == Optional.of("from s1")

        when: "s3 types a command, and s1 does too"
        held.hold(dir, Role.CLAUDE, "s3", "from s3")
        held.drop(dir, Role.CLAUDE, "s1")

        then:
        held.held(dir, Role.CLAUDE, "s1").isEmpty()
        held.held(dir, Role.CLAUDE, "s3") == Optional.of("from s3")
    }

    void "a later prompt in the same session replaces its hold"() {
        given:
        held.hold(dir, Role.CLAUDE, "s1", "first")

        when:
        held.hold(dir, Role.CLAUDE, "s1", "second")

        then:
        held.held(dir, Role.CLAUDE, "s1") == Optional.of("second")
    }

    void "a session's hold belongs to its role"() {
        given:
        held.hold(dir, Role.CODEX, "t1", "for codex")

        expect:
        held.held(dir, Role.CLAUDE, "t1").isEmpty()
        held.adopt(dir, Role.CLAUDE, "t1", toClaude).isEmpty()
        held.held(dir, Role.CODEX, "t1") == Optional.of("for codex")
    }

    void "the two clients' session identifiers are separate namespaces: holds under the same identifier coexist"() {
        given:
        held.hold(dir, Role.CLAUDE, "same", "Claude prompt")
        held.hold(dir, Role.CODEX, "same", "Codex prompt")

        expect:
        held.held(dir, Role.CLAUDE, "same") == Optional.of("Claude prompt")
        held.held(dir, Role.CODEX, "same") == Optional.of("Codex prompt")

        when:
        held.drop(dir, Role.CODEX, "same")

        then:
        held.held(dir, Role.CLAUDE, "same") == Optional.of("Claude prompt")
        held.held(dir, Role.CODEX, "same").isEmpty()
    }

    void "a version 3 database keeps its sessions, holds, and deliveries under the instance model"() {
        given: "a database the previous executable made"
        Path file = dir.resolve(Store.FILE_NAME)
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null)
                .locations("classpath:db/migration").target("3").load().migrate()
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.executeUpdate("INSERT INTO sessions VALUES ('codex', 't1', '2026-09-01T00:00:00Z', 0, 4, 1)")
                s.executeUpdate("INSERT INTO held_prompts VALUES ('claude', 's1', 'held before the upgrade')")
                s.executeUpdate("INSERT INTO held_prompts VALUES ('codex', 's1', 'codex held before the upgrade')")
                s.executeUpdate("INSERT INTO entries (id, created_at, sender, via, recipients, type, route, reply_to, caused_by, expects_reply, live, backlog, body)"
                        + " VALUES ('e1', '2026-09-01T00:00:00Z', 'operator', 'claude', 'codex', 'request', 'direct', NULL, NULL, 1, 'auto', 'confirm', 'hello')")
                s.executeUpdate("INSERT INTO deliveries (seq, role, session_id, pushed_at) VALUES (1, 'codex', 't1', '2026-09-01T00:00:01Z')")
                assert s.executeQuery("PRAGMA user_version").getInt(1) == 3
            }
        }

        expect: "the role's record is the unnamed instance's, with no process"
        with(sessions.load(dir, Fixtures.CODEX).get()) {
            id() == "t1"
            offset() == 4
            resumed()
            process() == null
        }
        held.held(dir, Role.CLAUDE, "s1") == Optional.of("held before the upgrade")
        held.held(dir, Role.CODEX, "s1") == Optional.of("codex held before the upgrade")
        context.getBean(Deliveries).pushedInto(dir, Fixtures.CODEX, "t1").keySet() == [1L] as Set
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.executeQuery("PRAGMA user_version").getInt(1) }
        } == Database.SCHEMA_VERSION
    }

    void "a version 1 database is migrated on the first read, so a role joined before the upgrade is still joined"() {
        given: "a database the previous executable made, with a joined role and one entry"
        Path file = dir.resolve(Store.FILE_NAME)
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null)
                .locations("classpath:db/migration").target("1").load().migrate()
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s ->
                s.executeUpdate("INSERT INTO sessions VALUES ('claude', 's1', '2026-09-01T00:00:00Z', 0, 0, 0)")
                s.executeUpdate("INSERT INTO entries (id, created_at, sender, via, recipients, type, route, reply_to, caused_by, expects_reply, live, backlog, body)"
                        + " VALUES ('e1', '2026-09-01T00:00:00Z', 'operator', 'claude', 'claude', 'request', 'direct', NULL, NULL, 1, 'auto', 'confirm', 'hello')")
                assert s.executeQuery("PRAGMA user_version").getInt(1) == 1
            }
        }

        expect: "reads see the session and the entry, not an absent store"
        sessions.load(dir, Fixtures.CLAUDE).get().id() == "s1"
        journal.end(dir) == 1
        held.held(dir, Role.CLAUDE, "s1").isEmpty()
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.executeQuery("PRAGMA user_version").getInt(1) }
        } == Database.SCHEMA_VERSION
    }

    void "a database another process is still creating reads as absent"() {
        given:
        Path file = dir.resolve(Store.FILE_NAME)
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.executeUpdate("CREATE TABLE placeholder (x)") }
        }

        expect:
        sessions.load(dir, Fixtures.CLAUDE).isEmpty()
        journal.end(dir) == 0
    }
}
