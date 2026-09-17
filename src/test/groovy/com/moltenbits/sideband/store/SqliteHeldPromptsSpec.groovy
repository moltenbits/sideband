package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Draft
import com.moltenbits.sideband.protocol.Role
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

    void "a hold from another session is dropped at adoption and nothing is journaled"() {
        given:
        held.hold(dir, Role.CLAUDE, "s1", "from s1")

        expect:
        held.adopt(dir, Role.CLAUDE, "s2", toClaude).isEmpty()
        journal.end(dir) == 0
        held.held(dir, Role.CLAUDE, "s1").isEmpty()
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
        sessions.load(dir, Role.CLAUDE).get().id() == "s1"
        journal.end(dir) == 1
        held.held(dir, Role.CLAUDE, "s1").isEmpty()
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.executeQuery("PRAGMA user_version").getInt(1) }
        } == 2
    }

    void "a database another process is still creating reads as absent"() {
        given:
        Path file = dir.resolve(Store.FILE_NAME)
        DriverManager.getConnection("jdbc:sqlite:" + file).withCloseable { c ->
            c.createStatement().withCloseable { s -> s.executeUpdate("CREATE TABLE placeholder (x)") }
        }

        expect:
        sessions.load(dir, Role.CLAUDE).isEmpty()
        journal.end(dir) == 0
    }
}
