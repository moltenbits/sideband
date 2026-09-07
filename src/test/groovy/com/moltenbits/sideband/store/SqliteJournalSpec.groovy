package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.journal.MessageIds
import com.moltenbits.sideband.journal.Read
import com.moltenbits.sideband.protocol.MessageType
import io.micronaut.context.ApplicationContext
import org.sqlite.SQLiteConfig
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SqliteJournalSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Closure<String> ids = Fixtures.sequentialIds()
    Journal journal = new SqliteJournal(context.getBean(Database), context.getBean(EntryRows), Fixtures.FIXED_CLOCK, ids as MessageIds)
    Path directory = Files.createTempDirectory("journal")
    Path database = directory.resolve(Store.FILE_NAME)

    void "the component is exposed only through its interfaces"() {
        expect:
        context.getBean(Journal) instanceof SqliteJournal
        context.getBean(Store) instanceof SqliteStore
    }

    void "appending a human draft assigns id, timestamp, and the first position, and creates the database"() {
        expect:
        !Files.exists(database)

        when:
        Entry entry = journal.append(directory, Fixtures.humanDraft())

        then:
        entry.metadata() == Fixtures.metadata()
        entry.body() == Fixtures.humanDraft().body()
        entry.seq() == 1
        Files.exists(database)
        journal.end(directory) == 1
    }

    void "consecutive appends get sequential ids and positions, and read back complete in order"() {
        when:
        Entry first = journal.append(directory, Fixtures.humanDraft())
        Entry second = journal.append(directory, Fixtures.agentDraft(causedBy: first.metadata().id()))
        Read all = journal.readAfter(directory, 0)

        then:
        first.metadata().id() == "019a"
        second.metadata().id() == "019b"
        second.seq() == first.seq() + 1
        all.start() == 0
        all.end() == second.seq()
        all.entries() == [first, second]
        all.entries()[1].metadata().causedBy() == "019a"
    }

    void "reading after a position returns only later entries, and the end stays put when there are none"() {
        given:
        Entry first = journal.append(directory, Fixtures.humanDraft())
        Entry second = journal.append(directory, Fixtures.agentDraft())

        expect:
        journal.readAfter(directory, first.seq()).entries()*.metadata()*.id() == ["019b"]
        journal.readAfter(directory, second.seq()) == Read.empty(second.seq())
    }

    void "a directory without a database reads as empty and finds nothing"() {
        expect:
        journal.readAfter(directory, 0) == Read.empty(0)
        journal.readAfter(directory, 7) == Read.empty(7)
        journal.find(directory, "019a").isEmpty()
        journal.end(directory) == 0
        !Files.exists(database)
    }

    void "find looks an entry up by id"() {
        given:
        journal.append(directory, Fixtures.humanDraft())
        Entry reply = journal.append(directory, Fixtures.agentDraft(type: MessageType.REPLY, replyTo: "019a", causedBy: null, expectsReply: false))

        expect:
        journal.find(directory, "019b").get() == reply
        journal.find(directory, "019b").get().metadata().replyTo() == "019a"
        journal.find(directory, "ghost").isEmpty()
    }

    void "a body keeps every byte: markers, another whole entry, blank lines, and non-ASCII"() {
        given:
        String body = "<!-- /sideband -->\n\n<!-- sideband:v1\n{\"id\":\"x\"}\n-->\n\n## h\n\nnested\n<!-- /sideband -->\n\n  trailing spaces  \né中😀"

        when:
        journal.append(directory, Fixtures.humanDraft(body))

        then:
        journal.readAfter(directory, 0).entries()[0].body() == body
    }

    void "an entry with a via, links, and every recipient round-trips its metadata exactly"() {
        given:
        journal.append(directory, Fixtures.humanDraft())
        def draft = Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE, Fixtures.OPERATOR], type: MessageType.REPLY,
                replyTo: "019a", causedBy: "019a", expectsReply: true)

        when:
        Entry entry = journal.append(directory, draft)

        then:
        with(journal.find(directory, entry.metadata().id()).get().metadata()) {
            from() == Fixtures.CODEX
            via() == null
            to() == [Fixtures.CLAUDE, Fixtures.OPERATOR]
            type() == MessageType.REPLY
            replyTo() == "019a"
            causedBy() == "019a"
            expectsReply()
            createdAt() == Fixtures.T0
        }
    }

    void "concurrent writers never interleave or lose entries"() {
        given:
        Journal shared = context.getBean(Journal)
        int writers = 8
        int perWriter = 25
        def pool = Executors.newFixedThreadPool(writers)
        def ready = new CountDownLatch(writers)
        def go = new CountDownLatch(1)
        def failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        when:
        (0..<writers).each { w ->
            pool.submit {
                ready.countDown()
                go.await()
                try {
                    perWriter.times { i -> shared.append(directory, Fixtures.humanDraft("writer $w entry $i\nsecond line")) }
                } catch (Throwable t) {
                    failures << t
                }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS)
        Read read = shared.readAfter(directory, 0)

        then:
        finished
        failures.isEmpty()
        read.entries().size() == writers * perWriter
        read.entries()*.seq() == (1..(writers * perWriter)).toList()
        read.entries()*.body().every { it ==~ /writer \d entry \d+\nsecond line/ }
        read.entries()*.body().toSet().size() == writers * perWriter
        read.entries()*.metadata()*.id().toSet().size() == writers * perWriter
    }

    void "a writer that cannot get the lock in time reports contention rather than corrupting anything"() {
        given: "another connection holds the write lock across the whole wait"
        journal.append(directory, Fixtures.humanDraft())
        def config = new SQLiteConfig()
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        def holder = config.createConnection("jdbc:sqlite:" + database)
        holder.autoCommit = false
        holder.createStatement().executeUpdate("INSERT INTO sessions VALUES ('claude','x','2026-09-02T16:42:00-05:00',0,0,0)")
        Duration patience = Duration.ofMillis(300)
        ApplicationContext impatient = ApplicationContext.run(["sideband.store.busy-timeout": "300ms"])
        Journal quick = impatient.getBean(Journal)

        when:
        long started = System.nanoTime()
        quick.append(directory, Fixtures.agentDraft())

        then:
        BusyException e = thrown()
        e.message.contains(database.toString())
        e.message.contains("300 ms")
        Duration.ofNanos(System.nanoTime() - started) >= patience
        journal.readAfter(directory, 0).entries().size() == 1

        cleanup:
        holder.rollback()
        holder.close()
        impatient.close()
    }
}
