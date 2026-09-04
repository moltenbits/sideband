package com.moltenbits.sideband.journal

import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileJournalSpec extends Specification {

    Journal journal = new FileJournal()
    Path directory = Files.createTempDirectory("journal")
    Path file = directory.resolve(Journal.FILE_NAME)

    void "an appended body is framed with a terminator line"() {
        when:
        Appended appended = journal.append(file, "hello")

        then:
        Files.readString(file) == "hello\n<!-- /sideband -->\n"
        appended == new Appended(0, Files.size(file))
    }

    void "a body that already ends with a newline is not given a second one"() {
        when:
        journal.append(file, "hello\n")

        then:
        Files.readString(file) == "hello\n<!-- /sideband -->\n"
    }

    void "consecutive appends report adjacent byte ranges"() {
        when:
        Appended first = journal.append(file, "one")
        Appended second = journal.append(file, "two")

        then:
        first.end() == second.start()
        second.end() == Files.size(file)
    }

    void "reading from the start returns every complete entry in order and where to resume"() {
        given:
        journal.append(file, "one")
        Appended last = journal.append(file, "two\nlines")

        when:
        Read read = journal.readCompleteFrom(file, 0)

        then:
        read.entries() == ["one", "two\nlines"]
        read.start() == 0
        read.end() == last.end()
    }

    void "reading from an entry's end skips everything before it"() {
        given:
        Appended first = journal.append(file, "one")
        journal.append(file, "two")

        expect:
        journal.readCompleteFrom(file, first.end()).entries() == ["two"]
    }

    void "reading a missing file or from its end returns nothing"() {
        expect:
        journal.readCompleteFrom(file, 0) == new Read(0, 0, [])

        when:
        Appended appended = journal.append(file, "one")

        then:
        journal.readCompleteFrom(file, appended.end()) == new Read(appended.end(), appended.end(), [])
    }

    void "an incomplete trailing entry is not returned and does not advance the end offset"() {
        given:
        Appended complete = journal.append(file, "one")
        Files.writeString(file, "partial write with no terminator\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)

        when:
        Read read = journal.readCompleteFrom(file, 0)

        then:
        read.entries() == ["one"]
        read.end() == complete.end()
    }

    void "multi-byte characters round-trip and offsets stay in bytes"() {
        given:
        String body = "héllo → wörld ✓"

        when:
        Appended appended = journal.append(file, body)
        Read read = journal.readCompleteFrom(file, 0)

        then:
        read.entries() == [body]
        appended.end() == Files.size(file)
        appended.end() > body.length()
    }

    void "concurrent writers never interleave entries"() {
        given:
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
                perWriter.times { i -> journal.append(file, "writer $w entry $i body line\nsecond line") }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS)
        Read read = journal.readCompleteFrom(file, 0)

        then:
        finished
        read.entries().size() == writers * perWriter
        read.entries().every { it ==~ /writer \d entry \d+ body line\nsecond line/ }
        read.entries().toSet().size() == writers * perWriter
        read.end() == Files.size(file)
    }

    void "a lock left by a dead process is reclaimed"() {
        given:
        Path lock = directory.resolve(FileJournal.LOCK_FILE_NAME)
        Files.writeString(lock, "999999999")

        when:
        journal.append(file, "after stale lock")

        then:
        journal.readCompleteFrom(file, 0).entries() == ["after stale lock"]
        !Files.exists(lock)
    }

    void "a lock held by a live process blocks the writer until it times out"() {
        given:
        Path lock = directory.resolve(FileJournal.LOCK_FILE_NAME)
        Files.writeString(lock, ProcessHandle.current().pid().toString())

        when:
        JournalLock.acquire(lock, java.time.Duration.ofMillis(200))

        then:
        thrown(LockTimeoutException)
        Files.exists(lock)
    }

    void "releasing the lock removes the lock file"() {
        given:
        Path lock = directory.resolve(FileJournal.LOCK_FILE_NAME)

        when:
        JournalLock held = JournalLock.acquire(lock, java.time.Duration.ofMillis(200))

        then:
        Files.exists(lock)

        when:
        held.close()

        then:
        !Files.exists(lock)
    }
}
