package com.moltenbits.sideband.command

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.SidebandCommand
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.Delivery
import com.moltenbits.sideband.protocol.Draft
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Route
import picocli.CommandLine

import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

/** `sideband log` is the readable form of the discussion: Markdown on stdout, nothing else. */
class LogCommandSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path state = home.resolve(".sideband")

    def setup() {
        run("init", "--repo", repo.toString(), "--skip-clients")
        stdout = new StringWriter()
    }

    void "renders every entry as a heading, its metadata, and the verbatim body; --after 0 reads them all, oldest first"() {
        given:
        Journal journal = context.getBean(Journal)
        def first = journal.append(state, Fixtures.humanDraft("@codex look at this\n\n## not a heading of ours\n<!-- /sideband -->", [Fixtures.CODEX]))
        def reply = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE, Fixtures.OPERATOR],
                type: MessageType.REPLY, replyTo: first.metadata().id(), causedBy: null, expectsReply: false, body: "done"))

        when:
        int code = run("log", "--repo", repo.toString(), "--after", "0")
        String text = stdout.toString()

        then:
        code == ExitCode.OK
        stderr.toString().isEmpty()
        text == """## Operator → codex (via claude)

- position: 1
- id: ${first.metadata().id()}
- created: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(first.metadata().createdAt())}
- type: request (expects a reply)
- to: repo/codex

@codex look at this

## not a heading of ours
<!-- /sideband -->

---

## codex → claude + Operator

- position: 2
- id: ${reply.metadata().id()}
- created: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(reply.metadata().createdAt())}
- type: reply
- to: repo/claude, operator
- reply_to: ${first.metadata().id()}

done

---

"""
    }

    void "a via that only repeats the author is left out of the heading, whatever the entry is"() {
        given:
        context.getBean(Journal).append(state, new Draft(Fixtures.CODEX, Fixtures.CODEX, [Fixtures.CLAUDE], MessageType.STATUS,
                Route.DIRECT, null, null, false, Delivery.DEFAULT, "fyi"))

        when:
        run("log", "--repo", repo.toString())

        then:
        stdout.toString().startsWith("## codex → claude\n\n")
    }

    void "a captured turn is headed by its author alone, since it was said in the author's own terminal"() {
        given:
        context.getBean(Journal).append(state, Draft.capturedTurn(Fixtures.CLAUDE, null, "Only `.git` is here."))

        when:
        run("log", "--repo", repo.toString())

        then:
        stdout.toString().startsWith("## claude → Operator\n\n")
    }

    void "--after and --limit select a range of positions"() {
        given:
        Journal journal = context.getBean(Journal)
        (1..4).each { journal.append(state, Fixtures.humanDraft("entry $it", [Fixtures.CODEX])) }

        expect:
        run("log", "--repo", repo.toString(), "--after", "2") == ExitCode.OK
        stdout.toString().findAll(/- position: (\d+)/) { it[1] } == ["3", "4"]

        when:
        stdout = new StringWriter()

        then:
        run("log", "--repo", repo.toString(), "--after", "1", "--limit", "2") == ExitCode.OK
        stdout.toString().findAll(/- position: (\d+)/) { it[1] } == ["2", "3"]
        stdout.toString().contains("entry 2") && stdout.toString().contains("entry 3")
        !stdout.toString().contains("entry 4")
    }

    void "by default the newest twenty entries print newest first, and a last line names the page before them"() {
        given:
        entries(25)

        expect:
        run("log", "--repo", repo.toString()) == ExitCode.OK
        positions() == (25..6).collect { it as String }
        stdout.toString().endsWith("---\n\nEarlier entries: `sideband log --before 6 --repo '$repo'`\n")
    }

    void "--before pages back, and the last page names no page before it"() {
        given:
        entries(25)

        expect:
        run("log", "--repo", repo.toString(), "--before", "6") == ExitCode.OK
        positions() == ["5", "4", "3", "2", "1"]
        stdout.toString().endsWith("entry 1\n\n---\n\n")
    }

    void "a page that ends exactly at the first entry names no page before it"() {
        given:
        entries(20)

        expect:
        run("log", "--repo", repo.toString()) == ExitCode.OK
        positions() == (20..1).collect { it as String }
        !stdout.toString().contains("Earlier entries")
    }

    void "--limit sets the page size, and the next page's line keeps it"() {
        given:
        entries(10)

        expect:
        run("log", "--repo", repo.toString(), "--limit", "3") == ExitCode.OK
        positions() == ["10", "9", "8"]
        stdout.toString().endsWith("Earlier entries: `sideband log --before 8 --limit 3 --repo '$repo'`\n")

        when:
        stdout = new StringWriter()

        then:
        run("log", "--repo", repo.toString(), "--before", "8", "--limit", "3") == ExitCode.OK
        positions() == ["7", "6", "5"]
        stdout.toString().endsWith("Earlier entries: `sideband log --before 5 --limit 3 --repo '$repo'`\n")

        when:
        stdout = new StringWriter()

        then:
        run("log", "--repo", repo.toString(), "--limit", "${Integer.MAX_VALUE}") == ExitCode.OK
        positions() == (10..1).collect { it as String }
        !stdout.toString().contains("Earlier entries")
    }

    void "the next page's command names the repository it read, quoted for the shell, so it reads the same one from anywhere"() {
        given:
        Path target = TempRepo.named("target", Files.createDirectories(Files.createTempDirectory("sideband").resolve("it's a \$HOME `dir`")))
        run("init", "--repo", target.toString(), "--skip-clients")
        ParticipantId codex = ParticipantId.of("target", "codex")
        Journal journal = context.getBean(Journal)
        (1..3).each { journal.append(state, Fixtures.humanDraft("target entry $it", [codex], codex)) }

        when:
        stdout = new StringWriter()
        run("log", "--repo", target.toString(), "--limit", "2")
        String command = (stdout.toString() =~ /Earlier entries: `sideband (log .*)`\n\z/)[0][1]
        stdout = new StringWriter()

        then:
        run(shellWords(command)) == ExitCode.OK
        positions() == ["1"]
        stdout.toString().contains("target entry 1")
    }

    void "a repository found from the working directory is left out of the next page's command"() {
        given:
        entries(3)
        String workingDirectory = System.getProperty("user.dir")
        System.setProperty("user.dir", repo.toString())

        when:
        int code = run("log", "--limit", "2")

        then:
        code == ExitCode.OK
        stdout.toString().endsWith("Earlier entries: `sideband log --before 2 --limit 2`\n")

        cleanup:
        System.setProperty("user.dir", workingDirectory)
    }

    /** Splits the words of a command the way the shell would. */
    private static String[] shellWords(String command) {
        Process shell = new ProcessBuilder("/bin/sh", "-c", 'eval "set -- $1"; for word; do printf "%s\\0" "$word"; done', "sh", command).start()
        String words = shell.inputStream.text
        assert shell.waitFor() == 0
        words.split("\u0000") as String[]
    }

    void "a page with nothing to show prints nothing at all"() {
        given:
        entries(3)

        expect:
        run("log", "--repo", repo.toString(), *arguments) == ExitCode.OK
        stdout.toString().isEmpty()

        where:
        arguments << [["--before", "1"], ["--limit", "0"], ["--before", "3", "--limit", "0"], ["--after", "3"]]
    }

    void "--id prints that one entry"() {
        given:
        Journal journal = context.getBean(Journal)
        def wanted = journal.append(state, Fixtures.humanDraft("the one", [Fixtures.CODEX]))
        journal.append(state, Fixtures.humanDraft("another", [Fixtures.CODEX]))

        expect:
        run("log", "--repo", repo.toString(), "--id", wanted.metadata().id()) == ExitCode.OK
        stdout.toString() == LogCommand.render(wanted, "repo")
    }

    void "--trace prints what an entry came from, the operator's prompt first"() {
        given:
        Journal journal = context.getBean(Journal)
        def prompt = journal.append(state, Fixtures.humanDraft("@claude fix it", [Fixtures.CLAUDE]))
        def request = journal.append(state, Fixtures.agentDraft(causedBy: prompt.metadata().id(), body: "review this"))
        journal.append(state, Fixtures.humanDraft("unrelated", [Fixtures.CODEX]))
        def reply = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.REPLY,
                replyTo: request.metadata().id(), causedBy: null, expectsReply: false, body: "looks fine"))

        expect:
        run("log", "--repo", repo.toString(), "--trace", reply.metadata().id()) == ExitCode.OK
        stdout.toString() == [prompt, request, reply].collect { LogCommand.render(it, "repo") }.join()

        when:
        stdout = new StringWriter()

        then: "the operator's own prompt is the whole trace"
        run("log", "--repo", repo.toString(), "--trace", prompt.metadata().id()) == ExitCode.OK
        stdout.toString() == LogCommand.render(prompt, "repo")
    }

    void "--trace follows caused_by over reply_to"() {
        given:
        Journal journal = context.getBean(Journal)
        def first = journal.append(state, Fixtures.humanDraft("@codex start", [Fixtures.CODEX]))
        def second = journal.append(state, Fixtures.humanDraft("@codex and then this", [Fixtures.CODEX]))
        def request = journal.append(state, Fixtures.agentDraft(causedBy: first.metadata().id()))
        def reply = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.REPLY,
                replyTo: request.metadata().id(), causedBy: second.metadata().id(), expectsReply: false, body: "both"))

        expect:
        run("log", "--repo", repo.toString(), "--trace", reply.metadata().id()) == ExitCode.OK
        positions() == [second.seq(), reply.seq()].collect { it as String }
    }

    void "--trace goes on past an operator entry that has a link, to an entry with none"() {
        given:
        Journal journal = context.getBean(Journal)
        def status = journal.append(state, new Draft(Fixtures.CLAUDE, null, [Fixtures.OPERATOR], MessageType.STATUS,
                Route.DIRECT, null, null, false, Delivery.DEFAULT, "should I go on?"))
        def answer = journal.append(state, new Draft(Fixtures.OPERATOR, Fixtures.CLAUDE, [Fixtures.CLAUDE], MessageType.REPLY,
                Route.DIRECT, status.metadata().id(), null, false, Delivery.DEFAULT, "yes"))
        def request = journal.append(state, Fixtures.agentDraft(causedBy: answer.metadata().id()))

        expect:
        run("log", "--repo", repo.toString(), "--trace", request.metadata().id()) == ExitCode.OK
        positions() == [status, answer, request]*.seq().collect { it as String }
    }

    void "--trace with an ancestor missing prints what it reached under a notice saying the start is missing"() {
        given:
        Journal journal = context.getBean(Journal)
        def orphan = journal.append(state, Fixtures.agentDraft(causedBy: "gone-from-this-journal"))
        def reply = journal.append(state, Fixtures.agentDraft(from: Fixtures.CODEX, to: [Fixtures.CLAUDE], type: MessageType.REPLY,
                replyTo: orphan.metadata().id(), causedBy: null, expectsReply: false, body: "ok"))

        expect:
        run("log", "--repo", repo.toString(), "--trace", reply.metadata().id()) == ExitCode.OK
        stdout.toString() == "**Incomplete trace.** ${orphan.metadata().id()} links to gone-from-this-journal, " +
                "which is not in this journal, so whatever started it is missing.\n\n" +
                LogCommand.render(orphan, "repo") + LogCommand.render(reply, "repo")
    }

    void "a trace whose links loop prints each entry once under a notice naming the loop"() {
        given:
        Map<String, Entry> byId = [:]
        links.each { String id, String cause ->
            byId[id] = new Entry(Fixtures.metadata(id: id, from: Fixtures.CLAUDE, via: null, to: [Fixtures.CODEX],
                    route: Route.DIRECT, causedBy: cause), "body of $id", byId.size() + 1)
        }

        when:
        def trace = LogCommand.trace(byId[start], { String id -> Optional.ofNullable(byId[id]) })

        then:
        trace.entries()*.metadata()*.id() == printed
        trace.notice() == "**Incomplete trace.** $last links to $repeated, which is already in this trace, so its links loop.\n\n"

        where:
        links                                      | start  || printed                  | last   | repeated
        ["019a": "019a"]                           | "019a" || ["019a"]                 | "019a" | "019a"
        ["019a": "019b", "019b": "019a"]           | "019a" || ["019b", "019a"]         | "019b" | "019a"
        ["019a": "019b", "019b": "019c", "019c": "019b"] | "019a" || ["019c", "019b", "019a"] | "019c" | "019b"
    }

    void "an unknown id is invalid input, and reading for it creates no database"() {
        given:
        Path empty = TempRepo.init()

        expect:
        run("log", "--repo", empty.toString(), option, "no-such-entry") == ExitCode.INVALID_INPUT
        stderr.toString().contains("no-such-entry")
        stdout.toString().isEmpty()
        !Files.exists(empty.resolve(".git/sideband/sideband.db"))

        where:
        option << ["--id", "--trace"]
    }

    void "a page read for a repository without a discussion creates no database"() {
        given:
        Path empty = TempRepo.init()

        expect:
        run("log", "--repo", empty.toString(), "--before", "5") == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(empty.resolve(".git/sideband/sideband.db"))
    }

    void "the ways of choosing entries are exclusive, and a single lookup takes no limit"() {
        given:
        def entry = context.getBean(Journal).append(state, Fixtures.humanDraft("x", [Fixtures.CODEX]))
        String id = entry.metadata().id()

        expect:
        run(["log", "--repo", repo.toString()] + arguments.collect { it.replace("ID", id) } as String[]) == ExitCode.INVALID_INPUT
        stdout.toString().isEmpty()

        where:
        arguments << [
                ["--id", "ID", "--trace", "ID"],
                ["--before", "3", "--after", "1"],
                ["--id", "ID", "--after", "0"],
                ["--trace", "ID", "--before", "2"],
                ["--id", "ID", "--limit", "1"],
                ["--trace", "ID", "--limit", "1"],
        ]
    }

    private void entries(int count) {
        Journal journal = context.getBean(Journal)
        (1..count).each { journal.append(state, Fixtures.humanDraft("entry $it", [Fixtures.CODEX])) }
    }

    private List<String> positions() {
        stdout.toString().findAll(/- position: (\d+)/) { it[1] }
    }

    void "a repository without a discussion prints nothing, succeeds, and creates no database"() {
        given:
        Path empty = TempRepo.init()

        expect:
        run("log", "--repo", empty.toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(empty.resolve(".git/sideband/sideband.db"))
    }

    void "an output that cannot be written is an I/O failure, not a success"() {
        given:
        context.getBean(Journal).append(state, Fixtures.humanDraft("something to print", [Fixtures.CODEX]))
        CommandLine cli = SidebandCommand.commandLine(context)
        cli.out = new PrintWriter(new Writer() {
            void write(char[] buffer, int offset, int length) throws IOException { throw new IOException("pipe closed") }
            void flush() throws IOException { throw new IOException("pipe closed") }
            void close() { }
        })
        cli.err = new PrintWriter(stderr, true)

        expect:
        cli.execute("log", "--repo", repo.toString()) == ExitCode.IO_FAILURE
        stderr.toString().contains("could not write the output")
    }

    void "negative ranges are invalid input"() {
        expect:
        run("log", "--repo", repo.toString(), "--after", "-1") == ExitCode.INVALID_INPUT
        run("log", "--repo", repo.toString(), "--limit", "-1") == ExitCode.INVALID_INPUT
        run("log", "--repo", repo.toString(), "--before", "0") == ExitCode.INVALID_INPUT
    }
}
