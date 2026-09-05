package com.moltenbits.sideband.waiting

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PollingWatcherSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    JournalWatcher watcher = context.getBean(JournalWatcher)
    Path file = Files.createTempDirectory("watch").resolve(Journal.FILE_NAME)

    void "the watcher is exposed only through its interface"() {
        expect:
        context.getBean(JournalWatcher) instanceof PollingWatcher
    }

    void "an entry that already exists past the offset returns immediately"() {
        given:
        journal.append(file, Fixtures.humanDraft("already there"))

        when:
        Waited waited = watcher.await(file, 0, Duration.ofSeconds(5))

        then:
        !waited.timedOut()
        waited.read().entries()*.body() == ["already there"]
    }

    void "the watcher wakes when an entry is appended while it is blocked"() {
        given:
        def waiting = CompletableFuture.supplyAsync { watcher.await(file, 0, null) }

        when:
        Thread.sleep(300)
        journal.append(file, Fixtures.humanDraft("late arrival"))
        Waited waited = waiting.get(5, TimeUnit.SECONDS)

        then:
        waited.read().entries()*.body() == ["late arrival"]
        waited.read().end() == Files.size(file)
    }

    void "entries before the offset do not wake the watcher"() {
        given:
        def first = journal.append(file, Fixtures.humanDraft("old"))

        expect:
        watcher.await(file, first.end() + 1, Duration.ofMillis(300)).timedOut()
    }

    void "the timeout elapses with an empty result when nothing arrives"() {
        when:
        long started = System.nanoTime()
        Waited waited = watcher.await(file, 0, Duration.ofMillis(300))

        then:
        waited.timedOut()
        waited.read().isEmpty()
        waited.read().end() == 0
        Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(300)
    }

    void "entries the filter rejects are consumed and the end offset moves past them even on timeout"() {
        given:
        journal.append(file, Fixtures.humanDraft("ignore me"))
        journal.append(file, Fixtures.humanDraft("ignore me too"))

        when:
        Waited waited = watcher.await(file, 0, Duration.ofMillis(300), { it.body().startsWith("keep") })

        then:
        waited.timedOut()
        waited.read().entries().isEmpty()
        waited.read().end() == Files.size(file)
    }

    void "the filter keeps waiting until a matching entry arrives and reports only matches"() {
        given:
        journal.append(file, Fixtures.humanDraft("ignore me"))
        def waiting = CompletableFuture.supplyAsync { watcher.await(file, 0, Duration.ofSeconds(5), { it.body().startsWith("keep") }) }

        when:
        Thread.sleep(300)
        journal.append(file, Fixtures.humanDraft("ignore again"))
        journal.append(file, Fixtures.humanDraft("keep this"))
        Waited waited = waiting.get(5, TimeUnit.SECONDS)

        then:
        !waited.timedOut()
        waited.read().start() == 0
        waited.read().entries()*.body() == ["keep this"]
        waited.read().end() == Files.size(file)
    }
}
