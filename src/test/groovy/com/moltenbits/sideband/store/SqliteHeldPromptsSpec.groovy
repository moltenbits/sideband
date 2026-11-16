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
        held.hold(dir, "repo", Role.CLAUDE, "s1", "review this")

        when:
        Optional<HeldPrompts.Adopted> adopted = held.adopt(dir, "repo", Role.CLAUDE, "s1", toClaude)

        then:
        adopted.get().entry().body() == "review this"
        adopted.get().entry().seq() == 1
        journal.end(dir) == 1
        held.held(dir, "repo", Role.CLAUDE, "s1").isEmpty()
        held.adopt(dir, "repo", Role.CLAUDE, "s1", toClaude).isEmpty()
    }

    void "adoption hands back the turn of the very hold it consumed"() {
        when: "a later hold replaces an earlier one"
        held.hold(dir, "repo", Role.CLAUDE, "s1", "first", "p-1")
        held.hold(dir, "repo", Role.CLAUDE, "s1", "second", "p-2")
        held.hold(dir, "repo", Role.CLAUDE, "s2", "other session", "q-1")
        HeldPrompts.Adopted adopted = held.adopt(dir, "repo", Role.CLAUDE, "s1", toClaude).get()

        then:
        adopted.entry().body() == "second"
        adopted.turn() == "p-2"

        when: "a host that names no turn"
        held.hold(dir, "repo", Role.CLAUDE, "s1", "third")

        then:
        held.adopt(dir, "repo", Role.CLAUDE, "s1", toClaude).get().turn() == null
    }

    void "a failure while journaling keeps the hold, so a retried join can adopt it"() {
        given:
        held.hold(dir, "repo", Role.CLAUDE, "s1", "review this")

        when:
        held.adopt(dir, "repo", Role.CLAUDE, "s1", { String prompt -> throw new IllegalStateException("disk full") })

        then:
        thrown(IllegalStateException)
        journal.end(dir) == 0
        held.held(dir, "repo", Role.CLAUDE, "s1") == Optional.of("review this")
    }

    void "each session's hold is its own: holding, adopting, or dropping in one leaves the other"() {
        given: "two sessions of one role, neither joined, each with a prompt"
        held.hold(dir, "repo", Role.CLAUDE, "s1", "from s1")
        held.hold(dir, "repo", Role.CLAUDE, "s2", "from s2")

        expect: "the second hold replaced nothing"
        held.held(dir, "repo", Role.CLAUDE, "s1") == Optional.of("from s1")

        when: "s2 joins"
        Optional<HeldPrompts.Adopted> adopted = held.adopt(dir, "repo", Role.CLAUDE, "s2", toClaude)

        then: "only its own prompt is journaled, and s1's hold waits for s1's join"
        adopted.get().entry().body() == "from s2"
        journal.end(dir) == 1
        held.held(dir, "repo", Role.CLAUDE, "s1") == Optional.of("from s1")

        when: "s3 types a command, and s1 does too"
        held.hold(dir, "repo", Role.CLAUDE, "s3", "from s3")
        held.drop(dir, "repo", Role.CLAUDE, "s1")

        then:
        held.held(dir, "repo", Role.CLAUDE, "s1").isEmpty()
        held.held(dir, "repo", Role.CLAUDE, "s3") == Optional.of("from s3")
    }

    void "a later prompt in the same session replaces its hold"() {
        given:
        held.hold(dir, "repo", Role.CLAUDE, "s1", "first")

        when:
        held.hold(dir, "repo", Role.CLAUDE, "s1", "second")

        then:
        held.held(dir, "repo", Role.CLAUDE, "s1") == Optional.of("second")
    }

    void "a session's hold belongs to its role"() {
        given:
        held.hold(dir, "repo", Role.CODEX, "t1", "for codex")

        expect:
        held.held(dir, "repo", Role.CLAUDE, "t1").isEmpty()
        held.adopt(dir, "repo", Role.CLAUDE, "t1", toClaude).isEmpty()
        held.held(dir, "repo", Role.CODEX, "t1") == Optional.of("for codex")
    }

    void "the two clients' session identifiers are separate namespaces: holds under the same identifier coexist"() {
        given:
        held.hold(dir, "repo", Role.CLAUDE, "same", "Claude prompt")
        held.hold(dir, "repo", Role.CODEX, "same", "Codex prompt")

        expect:
        held.held(dir, "repo", Role.CLAUDE, "same") == Optional.of("Claude prompt")
        held.held(dir, "repo", Role.CODEX, "same") == Optional.of("Codex prompt")

        when:
        held.drop(dir, "repo", Role.CODEX, "same")

        then:
        held.held(dir, "repo", Role.CLAUDE, "same") == Optional.of("Claude prompt")
        held.held(dir, "repo", Role.CODEX, "same").isEmpty()
    }

    void "a host session holds a prompt per project: the same session id in two projects keeps two holds"() {
        given:
        held.hold(dir, "alpha", Role.CLAUDE, "same", "typed in alpha")
        held.hold(dir, "beta", Role.CLAUDE, "same", "typed in beta")

        expect:
        held.held(dir, "alpha", Role.CLAUDE, "same") == Optional.of("typed in alpha")
        held.held(dir, "beta", Role.CLAUDE, "same") == Optional.of("typed in beta")

        when:
        held.drop(dir, "alpha", Role.CLAUDE, "same")

        then:
        held.held(dir, "alpha", Role.CLAUDE, "same").isEmpty()
        held.held(dir, "beta", Role.CLAUDE, "same") == Optional.of("typed in beta")
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
