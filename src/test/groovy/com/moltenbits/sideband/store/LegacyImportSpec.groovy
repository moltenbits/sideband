package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.journal.Read
import com.moltenbits.sideband.pending.Pending
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

import static java.nio.charset.StandardCharsets.UTF_8

/** A discussion recorded as journal.md plus session files comes over into the database once, positions and all. */
class LegacyImportSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    Path dir = Files.createTempDirectory("legacy")
    Path file = dir.resolve("journal.md")

    /** One entry in the retired on-disk format, with the unknown field old writers used to emit. */
    static String legacyEntry(String id, String from, String via, List<String> to, String type, String replyTo, String causedBy, String body) {
        byte[] bytes = body.getBytes(UTF_8)
        String viaJson = via == null ? "null" : "\"$via\""
        String replyJson = replyTo == null ? "null" : "\"$replyTo\""
        String causedJson = causedBy == null ? "null" : "\"$causedBy\""
        String route = to.size() > 1 ? "broadcast" : "direct"
        boolean expects = type == "request"
        "<!-- sideband:v1\n" +
                "{\"id\":\"$id\",\"created_at\":\"2026-09-06T09:28:58-05:00\",\"from\":\"$from\",\"via\":$viaJson,\"to\":[${to.collect { "\"$it\"" }.join(",")}]," +
                "\"type\":\"$type\",\"route\":\"$route\",\"reply_to\":$replyJson,\"caused_by\":$causedJson,\"expects_reply\":$expects," +
                "\"heartbeat_seconds\":null,\"delivery\":{\"live\":\"auto\",\"backlog\":\"confirm\"},\"body_bytes\":${bytes.length}}\n" +
                "-->\n\n## heading\n\n" + body + "\n<!-- /sideband -->\n\n"
    }

    void "entries, their metadata, and the session records come over, and the old files stay put"() {
        given: "a journal with three entries, one of which contains a whole entry as its body, and an aborted fragment"
        String first = legacyEntry("aaa", "operator", "claude", ["codex"], "request", null, null, "@codex look\n<!-- /sideband -->\nstill the body")
        String second = legacyEntry("bbb", "codex", null, ["claude"], "ack", "aaa", null, "received")
        String fragment = "<!-- sideband:v1\n{\"id\":\"crashed\",\"created_at\"\n<!-- sideband:aborted -->\n"
        String third = legacyEntry("ccc", "codex", null, ["claude", "operator"], "reply", "aaa", null, legacyEntry("zzz", "operator", "codex", ["claude"], "request", null, null, "nested"))
        Files.writeString(file, first + second + fragment + third)
        long endOfFirst = first.getBytes(UTF_8).length - 1  // the blank separator line follows each entry
        long endOfSecond = endOfFirst + 1 + second.getBytes(UTF_8).length - 1
        Files.createDirectories(dir.resolve("sessions"))
        Files.writeString(dir.resolve("sessions/codex.json"),
                "{\"id\":\"old-thread\",\"started_at\":\"2026-09-06T09:30:16-05:00\",\"parent_pid\":66503,\"watermark\":$endOfFirst,\"offset\":${endOfSecond + 1},\"resumed\":false}")
        Files.writeString(dir.resolve("sessions/claude.json"),
                "{\"id\":\"s-claude\",\"started_at\":\"2026-09-07T17:36:11-05:00\",\"watermark\":${endOfSecond + 10},\"offset\":0,\"resumed\":true}")

        when: "the first read of the state directory imports everything"
        Read all = journal.readAfter(dir, 0)

        then:
        Files.exists(dir.resolve(Store.FILE_NAME))
        all.entries()*.metadata()*.id() == ["aaa", "bbb", "ccc"]
        all.entries()*.seq() == [1L, 2L, 3L]
        all.entries()[0].body() == "@codex look\n<!-- /sideband -->\nstill the body"
        all.entries()[2].body().startsWith("<!-- sideband:v1\n")
        all.entries()[2].body().endsWith("nested\n<!-- /sideband -->\n\n")
        with(all.entries()[1].metadata()) {
            from() == Fixtures.CODEX
            via() == null
            to() == [Fixtures.CLAUDE]
            replyTo() == "aaa"
            createdAt().toString() == "2026-09-06T09:28:58-05:00"
        }
        all.entries()[2].metadata().to() == [Fixtures.CLAUDE, Fixtures.OPERATOR]

        and: "byte offsets became positions: the last entry that ended at or before them"
        with(sessions.load(dir, Role.CODEX).get()) {
            id() == "old-thread"
            watermark() == 1
            offset() == 2
            !resumed()
        }
        with(sessions.load(dir, Role.CLAUDE).get()) {
            id() == "s-claude"
            watermark() == 2
            offset() == 0
            resumed()
        }

        and: "what each role has to look at is the same as before: the reply closed the request, and Claude has it to read"
        context.getBean(Pending).report(dir, Role.CODEX).open().isEmpty()
        context.getBean(Pending).report(dir, Role.CODEX).inProgress().isEmpty()
        context.getBean(Pending).report(dir, Role.CLAUDE).updates()*.metadata()*.id() == ["ccc"]

        and: "the old files are untouched"
        Files.readString(file) == first + second + fragment + third
        Files.exists(dir.resolve("sessions/codex.json"))
    }

    void "the import happens once: later appends follow the imported entries and the journal file is not read again"() {
        given:
        Files.writeString(file, legacyEntry("aaa", "operator", "claude", ["codex"], "request", null, null, "first"))

        when:
        def appended = journal.append(dir, Fixtures.humanDraft("second", [Fixtures.CODEX]))
        Files.writeString(file, legacyEntry("ignored", "operator", "claude", ["codex"], "request", null, null, "written after the import"))

        then:
        appended.seq() == 2
        journal.readAfter(dir, 0).entries()*.metadata()*.id() == ["aaa", appended.metadata().id()]
        journal.find(dir, "ignored").isEmpty()
        journal.end(dir) == 2
    }

    void "a journal with no session files imports entries alone"() {
        given:
        Files.writeString(file, legacyEntry("aaa", "operator", "claude", ["codex"], "request", null, null, "hello"))

        expect:
        journal.end(dir) == 1
        sessions.load(dir, Role.CODEX).isEmpty()
        sessions.load(dir, Role.CLAUDE).isEmpty()
    }

    void "a malformed region is skipped with a warning and the rest comes over"() {
        given:
        Files.writeString(file, legacyEntry("aaa", "operator", "claude", ["codex"], "request", null, null, "one")
                + "<!-- sideband:v1\n{not json}\n-->\n\n## h\n\nx\n<!-- /sideband -->\n\n"
                + legacyEntry("bbb", "operator", "claude", ["codex"], "request", null, null, "two"))

        expect:
        journal.readAfter(dir, 0).entries()*.metadata()*.id() == ["aaa", "bbb"]
    }

    void "a directory with neither a database nor a journal stays empty and gets no database from a read"() {
        expect:
        journal.readAfter(dir, 0).isEmpty()
        !Files.exists(dir.resolve(Store.FILE_NAME))
    }

    void "a byte offset maps to the count of entries that ended at or before it"() {
        given:
        def entries = [entry(0, 10), entry(11, 20), entry(21, 30)]

        expect:
        LegacyImport.position(entries, 0) == 0
        LegacyImport.position(entries, 9) == 0
        LegacyImport.position(entries, 10) == 1
        LegacyImport.position(entries, 20) == 2
        LegacyImport.position(entries, 25) == 2
        LegacyImport.position(entries, 999) == 3
    }

    private static LegacyJournal.LegacyEntry entry(long start, long end) {
        new LegacyJournal.LegacyEntry(Fixtures.metadata(), "x", start, end)
    }
}
