package com.moltenbits.sideband.journal

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.locking.Locks
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileJournalSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Closure<String> ids = Fixtures.sequentialIds()
    Journal journal = new FileJournal(new EntryCodec(context.getBean(ObjectMapper)), Fixtures.FIXED_CLOCK, ids as MessageIds, context.getBean(Locks))
    Path directory = Files.createTempDirectory("journal")
    Path file = directory.resolve(Journal.FILE_NAME)

    void "appending a human draft assigns id and timestamp and writes the full entry"() {
        when:
        Entry entry = journal.append(file, Fixtures.humanDraft())

        then:
        entry.metadata() == Fixtures.metadata()
        entry.start() == 0
        entry.end() == Files.size(file) - 1 // the blank separator line follows the entry
        Files.readString(file).startsWith("<!-- sideband:v1\n{\"id\":\"019a\",\"created_at\":\"2026-09-02T16:42:00-05:00\"")
        Files.readString(file).endsWith("@all independently review the proposed database migration.\n<!-- /sideband -->\n\n")
    }

    void "consecutive appends get sequential ids and adjacent ranges"() {
        when:
        Entry first = journal.append(file, Fixtures.humanDraft())
        Entry second = journal.append(file, Fixtures.agentDraft(causedBy: first.metadata().id()))

        then:
        first.metadata().id() == "019a"
        second.metadata().id() == "019b"
        second.start() == first.end() + 1
        journal.readCompleteFrom(file, 0).entries()*.body() == [Fixtures.humanDraft().body(), Fixtures.agentDraft().body()]
    }

    void "reading from an entry's end returns only later entries"() {
        given:
        Entry first = journal.append(file, Fixtures.humanDraft())
        journal.append(file, Fixtures.agentDraft())

        expect:
        journal.readCompleteFrom(file, first.end()).entries()*.metadata()*.id() == ["019b"]
    }

    void "a missing file or an offset at the end reads as empty"() {
        expect:
        journal.readCompleteFrom(file, 0) == Read.empty(0)

        when:
        Entry entry = journal.append(file, Fixtures.humanDraft())

        then:
        journal.readCompleteFrom(file, Files.size(file)) == Read.empty(Files.size(file))
    }

    void "the next writer closes a fragment left by a crash, and the reader skips it"() {
        given:
        journal.append(file, Fixtures.humanDraft())
        long complete = Files.size(file)
        Files.writeString(file, "<!-- sideband:v1\n{\"id\":\"crashed\",\"created_at\"", StandardCharsets.UTF_8, StandardOpenOption.APPEND)

        expect: "the fragment is invisible and blocks the end offset"
        journal.readCompleteFrom(file, 0).entries().size() == 1
        journal.readCompleteFrom(file, 0).end() == complete

        when:
        Entry after = journal.append(file, Fixtures.agentDraft())
        Read read = journal.readCompleteFrom(file, 0)

        then:
        read.entries()*.metadata()*.id() == ["019a", "019b"]
        read.diagnostics().size() == 1
        read.diagnostics()[0].offset() == complete
        read.end() == Files.size(file)
        after.end() == Files.size(file) - 1
        Files.readString(file).contains("\"created_at\"\n<!-- sideband:aborted -->\n<!-- sideband:v1\n")
    }

    void "concurrent writers never interleave entries"() {
        given:
        Journal shared = context.getBean(Journal)
        int writers = 8
        int perWriter = 25
        def pool = Executors.newFixedThreadPool(writers)
        def ready = new CountDownLatch(writers)
        def go = new CountDownLatch(1)

        when:
        (0..<writers).each { w ->
            pool.submit {
                ready.countDown()
                go.await()
                perWriter.times { i -> shared.append(file, Fixtures.humanDraft("writer $w entry $i\nsecond line")) }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS)
        Read read = shared.readCompleteFrom(file, 0)

        then:
        finished
        read.diagnostics().isEmpty()
        read.entries().size() == writers * perWriter
        read.entries()*.body().every { it ==~ /writer \d entry \d+\nsecond line/ }
        read.entries()*.body().toSet().size() == writers * perWriter
        read.entries()*.metadata()*.id().toSet().size() == writers * perWriter
        read.end() == Files.size(file)
    }

    void "the context wires the journal through its interface with a real clock and ids"() {
        given:
        Journal wired = context.getBean(Journal)

        when:
        Entry entry = wired.append(file, Fixtures.agentDraft(type: MessageType.STATUS, expectsReply: false))

        then:
        wired instanceof FileJournal
        UUID.fromString(entry.metadata().id())
        entry.metadata().createdAt().offset != null
    }
}
