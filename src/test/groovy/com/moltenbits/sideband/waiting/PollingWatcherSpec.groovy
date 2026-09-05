package com.moltenbits.sideband.waiting

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.journal.Read
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

        expect:
        watcher.await(file, 0, Duration.ofSeconds(5)).get().entries()*.body() == ["already there"]
    }

    void "the watcher wakes when an entry is appended while it is blocked"() {
        given:
        def waiting = CompletableFuture.supplyAsync { watcher.await(file, 0) }

        when:
        Thread.sleep(300)
        journal.append(file, Fixtures.humanDraft("late arrival"))
        Read read = waiting.get(5, TimeUnit.SECONDS)

        then:
        read.entries()*.body() == ["late arrival"]
        read.end() == Files.size(file)
    }

    void "entries before the offset do not wake the watcher"() {
        given:
        def first = journal.append(file, Fixtures.humanDraft("old"))

        expect:
        watcher.await(file, first.end() + 1, Duration.ofMillis(300)).isEmpty()
    }

    void "the timeout elapses with an empty result when nothing arrives"() {
        when:
        long started = System.nanoTime()
        Optional<Read> result = watcher.await(file, 0, Duration.ofMillis(300))

        then:
        result.isEmpty()
        Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(300)
    }
}
