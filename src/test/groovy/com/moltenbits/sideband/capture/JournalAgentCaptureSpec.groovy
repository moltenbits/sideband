package com.moltenbits.sideband.capture

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.capture.AgentCapture.Hook
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.CaptureLedger
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The capture contract of REQUIREMENTS.md 14.16a, against real transcripts written the way
 * each host writes them: from the transcript, every message once, in order, one entry per
 * turn; from the payload, only a final the transcript cannot give, and never both for one turn.
 */
class JournalAgentCaptureSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run(
            ["sideband.capture.final-wait": "400ms", "sideband.capture.poll-interval": "20ms"])

    AgentCapture capture = context.getBean(AgentCapture)
    Journal journal = context.getBean(Journal)
    Sessions sessions = context.getBean(Sessions)
    CaptureLedger ledger = context.getBean(CaptureLedger)
    Path dir = Files.createTempDirectory("capture")
    Path claude = dir.resolve("claude.jsonl")
    Path codex = dir.resolve("codex.jsonl")

    def setup() {
        sessions.join(dir, Fixtures.CLAUDE, "s1")
        sessions.join(dir, Fixtures.CODEX, "c1")
        // Each host creates its transcript when the session starts, before the first prompt.
        Files.createFile(claude)
        Files.createFile(codex)
    }

    String toJson(Map record) {
        context.getBean(ObjectMapper).writeValueAsString(record)
    }

    // Claude Code 2.1.296's records, trimmed to what is read.

    String prompt(String promptId, String text = "do it") {
        toJson([type: "user", promptId: promptId, isSidechain: false, message: [role: "user", content: text]]) + "\n"
    }

    /** Each record has the uuid the host gave it; a file the host rewrites keeps them, so a test that rewrites one names them. */
    String text(String said, String stopReason, String uuid) {
        toJson([type: "assistant", uuid: uuid, isSidechain: false, message: [model: "claude-opus-5-5", role: "assistant",
                stop_reason: stopReason, content: [[type: "text", text: said]]]]) + "\n"
    }

    String commentary(String said, String uuid = UUID.randomUUID().toString()) { text(said, "tool_use", uuid) }

    String answer(String said, String uuid = UUID.randomUUID().toString()) { text(said, "end_turn", uuid) }

    String tool(String promptId) {
        toJson([type: "assistant", isSidechain: false, message: [model: "claude-opus-5-5", stop_reason: "tool_use",
                content: [[type: "tool_use", id: "toolu", name: "Bash", input: [command: "ls"]]]]]) + "\n" +
                toJson([type: "user", promptId: promptId, message: [content: [[type: "tool_result", tool_use_id: "toolu", content: "x"]]]]) + "\n"
    }

    // Codex 0.162.1's records, trimmed to what is read.

    String started(String turn) {
        toJson([type: "event_msg", payload: [type: "task_started", turn_id: turn]]) + "\n"
    }

    String codexSaid(String said, String phase) {
        toJson([type: "response_item", payload: [type: "message", id: "msg_" + UUID.randomUUID(), role: "assistant", phase: phase,
                content: [[type: "output_text", text: said]]]]) + "\n"
    }

    void write(Path file, String... lines) {
        Files.writeString(file, lines.join(""), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    Hook claudeHook(String turn, Path transcript = claude, String session = "s1") {
        new Hook(Role.CLAUDE, session, turn, transcript, null)
    }

    Hook codexHook(String turn) {
        new Hook(Role.CODEX, "c1", turn, codex, null)
    }

    List<Entry> captured() {
        journal.readAfter(dir, 0).entries().findAll { it.metadata().isCapturedTurn() }
    }

    /** The operator's prompt arrives: earlier words are captured, then the prompt's own turn is noted. */
    void operatorPrompts(Hook hook, String entryId) {
        capture.beforePrompt(dir, hook)
        capture.prompted(dir, hook, entryId)
    }

    void "the component is exposed only through its interface"() {
        expect:
        capture instanceof JournalAgentCapture
    }

    void "a turn's commentary and final message are recorded as one captured turn, caused by its prompt"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("I'll list the directory first."), tool("p-1"), answer("Only `.git` is here."))

        when:
        List<Entry> entries = capture.turnEnded(dir, claudeHook("p-1"), "Only `.git` is here.")

        then:
        entries.size() == 1
        with(entries[0].metadata()) {
            from() == Fixtures.CLAUDE
            via() == Fixtures.CLAUDE
            to() == [Fixtures.OPERATOR]
            type() == MessageType.STATUS
            !expectsReply()
            causedBy() == "prompt-1"
        }
        entries[0].body() == "I'll list the directory first.\n\nOnly `.git` is here."
    }

    void "a replayed Stop records nothing, after a transcript final, a payload final, or a prompt that already read it"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), answer("Done."))
        capture.turnEnded(dir, claudeHook("p-1"), "Done.")

        expect: "right away, and after several later turns"
        capture.turnEnded(dir, claudeHook("p-1"), "Done.").isEmpty()
        (2..4).each { n ->
            operatorPrompts(claudeHook("p-$n"), "prompt-$n")
            write(claude, prompt("p-$n"), answer("Turn $n."))
            capture.turnEnded(dir, claudeHook("p-$n"), "Turn $n.")
        }
        capture.turnEnded(dir, claudeHook("p-1"), "Done.").isEmpty()

        when: "the final was read by the next prompt before the turn's own Stop ran"
        write(claude, prompt("p-5"), answer("Five."))
        operatorPrompts(claudeHook("p-6"), "prompt-6")

        then:
        capture.turnEnded(dir, claudeHook("p-5"), "Five.").isEmpty()

        when: "a turn whose final came from the payload is replayed"
        Path gone = dir.resolve("gone.jsonl")
        capture.turnEnded(dir, claudeHook("q-1", gone, "s9"), "From the payload.")
        sessions.join(dir, new ParticipantId("claude:fable"), "s9")
        capture.turnEnded(dir, claudeHook("q-1", gone, "s9"), "From the payload.")
        capture.turnEnded(dir, claudeHook("q-1", gone, "s9"), "From the payload.")

        then:
        captured()*.body().count { it == "From the payload." } == 1
        captured()*.body() == ["Done.", "Turn 2.", "Turn 3.", "Turn 4.", "Five.", "From the payload."]
    }

    void "a final message the host writes while the Stop hook runs is waited for"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Checking."), tool("p-1"))
        Thread.start {
            sleep(100)
            write(claude, answer("All clear."))
        }

        when:
        List<Entry> entries = capture.turnEnded(dir, claudeHook("p-1"), "All clear.")

        then:
        entries*.body() == ["Checking.\n\nAll clear."]
    }

    void "a final the host has not written in time is deferred to the next capture, never taken from the payload"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Checking."), tool("p-1"))

        when:
        List<Entry> atStop = capture.turnEnded(dir, claudeHook("p-1"), "All clear.")

        then: "the commentary is recorded now"
        atStop*.body() == ["Checking."]

        when: "the final lands late, then the operator speaks again"
        write(claude, answer("All clear."))
        List<Entry> atPrompt = capture.beforePrompt(dir, claudeHook("p-2"))

        then: "it is recorded once, before the next prompt, still under its own turn's prompt"
        atPrompt*.body() == ["All clear."]
        atPrompt[0].metadata().causedBy() == "prompt-1"
        captured()*.body() == ["Checking.", "All clear."]
    }

    void "commentary and a final with the same words are two messages, both recorded"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Done"), tool("p-1"), answer("Done"))

        expect:
        capture.turnEnded(dir, claudeHook("p-1"), "Done")*.body() == ["Done\n\nDone"]
    }

    void "an unreadable transcript gives the payload's final for an untouched turn, and that turn is skipped on recovery"() {
        given: "the cursor sits before T0, whose words were written but not yet read"
        operatorPrompts(claudeHook("p-0"), "prompt-0")
        Path moved = dir.resolve("moved.jsonl")
        Files.move(claude, moved)
        write(moved, prompt("p-0"), answer("Zero."))

        when: "T1 and T2 end while the transcript is unavailable"
        List<Entry> one = capture.turnEnded(dir, claudeHook("p-1"), "One.")
        List<Entry> oneAgain = capture.turnEnded(dir, claudeHook("p-1"), "One.")
        List<Entry> two = capture.turnEnded(dir, claudeHook("p-2"), "Two.")

        then:
        one*.body() == ["One."]
        oneAgain.isEmpty()
        two*.body() == ["Two."]

        when: "the transcript returns holding T0, T1 and T2, and T3 follows"
        write(moved, prompt("p-1"), commentary("Thinking about one."), tool("p-1"), answer("One."),
                prompt("p-2"), answer("Two."), prompt("p-3"), answer("Three."))
        Files.move(moved, claude)
        List<Entry> recovered = capture.turnEnded(dir, claudeHook("p-3"), "Three.")

        then: "T0 and T3 come from the transcript; T1 and T2 are not recorded again, in part or whole"
        recovered*.body() == ["Zero.", "Three."]
        capture.turnEnded(dir, claudeHook("p-1"), "One.").isEmpty()
        capture.turnEnded(dir, claudeHook("p-2"), "Two.").isEmpty()
        captured()*.body() == ["One.", "Two.", "Zero.", "Three."]
    }

    void "a turn already recorded from the transcript stays there when the transcript becomes unreadable"() {
        given: "a flush records T1's commentary from the transcript"
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Starting."), tool("p-1"))
        capture.flush(dir, claudeHook("p-1"))

        when: "T1 ends while the transcript is unavailable"
        Path moved = dir.resolve("moved.jsonl")
        Files.move(claude, moved)
        List<Entry> atStop = capture.turnEnded(dir, claudeHook("p-1"), "Finished.")

        then: "its final is deferred, not taken from the payload"
        atStop.isEmpty()

        when: "the transcript returns"
        write(moved, answer("Finished."))
        Files.move(moved, claude)
        List<Entry> recovered = capture.beforePrompt(dir, claudeHook("p-2"))

        then:
        recovered*.body() == ["Finished."]
        captured()*.body() == ["Starting.", "Finished."]
    }

    void "a turn no prompt started has no cause, and a late fragment keeps its own turn's prompt"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Working."), tool("p-1"))
        capture.turnEnded(dir, claudeHook("p-1"), "Done one.")

        when: "T1's final lands late, then a pushed envelope starts T2 with no prompt of the operator's"
        write(claude, answer("Done one."), prompt("peer-2", "[Sideband message]"), answer("Codex says hi."))
        List<Entry> entries = capture.turnEnded(dir, claudeHook("peer-2"), "Codex says hi.")

        then:
        entries*.body() == ["Done one.", "Codex says hi."]
        entries*.metadata()*.causedBy() == ["prompt-1", null]
    }

    void "nothing is recorded for a session that holds no instance, and nothing said before it joins ever is"() {
        given: "a session that has not joined talks for a turn"
        Hook other = claudeHook("p-1", claude, "s2")
        capture.beforePrompt(dir, other)
        write(claude, prompt("p-1"), answer("Before joining."))

        expect:
        capture.turnEnded(dir, other, "Before joining.").isEmpty()

        when: "the operator types /sideband, the session joins during that turn, and the turn ends"
        capture.beforePrompt(dir, claudeHook("p-2", claude, "s2"))
        write(claude, prompt("p-2", "/sideband"), commentary("Joining."), tool("p-2"))
        sessions.join(dir, Fixtures.CLAUDE, "s2")
        write(claude, answer("Joined as Claude."))
        List<Entry> entries = capture.turnEnded(dir, claudeHook("p-2", claude, "s2"), "Joined as Claude.")

        then: "the whole joining turn is recorded, and nothing from before it"
        entries*.body() == ["Joining.\n\nJoined as Claude."]
        captured()*.body() == ["Joining.\n\nJoined as Claude."]
    }

    void "a client that has never joined here records nothing, though commands would act as its unnamed instance"() {
        given: "a repository where only Codex has joined, and one where nobody has"
        Path codexOnly = Files.createTempDirectory("codex-only")
        sessions.join(codexOnly, Fixtures.CODEX, "c1")
        Path nobody = Files.createTempDirectory("nobody")
        write(claude, prompt("p-1"), answer("Not joined."))
        Path missing = dir.resolve("missing.jsonl")

        expect: "neither from the transcript nor from the payload"
        [codexOnly, nobody].every { repo ->
            capture.beforePrompt(repo, claudeHook("p-1", claude, "never-joined")).isEmpty() &&
                    capture.turnEnded(repo, claudeHook("p-1", claude, "never-joined"), "Not joined.").isEmpty() &&
                    capture.flush(repo, claudeHook("p-1", claude, "never-joined")).isEmpty() &&
                    capture.turnEnded(repo, claudeHook("p-2", missing, "never-joined"), "From the payload.").isEmpty() &&
                    journal.end(repo) == 0
        }

        and: "the commit itself refuses a session that holds no record, even for the unnamed instance"
        def state = ledger.load(nobody, Role.CLAUDE, "never-joined")
        ledger.commit(nobody, Role.CLAUDE, "never-joined", null, state, Fixtures.CLAUDE,
                [com.moltenbits.sideband.protocol.Draft.capturedTurn(Fixtures.CLAUDE, null, "forced")], [], null, []).empty
    }

    void "a Stop replayed from the transcript a session used before records nothing again"() {
        given: "p-1 is captured from one file, then the session's transcript is another, and p-2 is captured from it"
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), answer("First file."))
        capture.turnEnded(dir, claudeHook("p-1"), "First file.")
        Path other = dir.resolve("other.jsonl")
        write(other, prompt("p-2"), answer("Second file."))
        capture.turnEnded(dir, claudeHook("p-2", other), "Second file.")

        expect: "replaying the old file's Stop, then the new one's"
        capture.turnEnded(dir, claudeHook("p-1"), "First file.").isEmpty()
        capture.turnEnded(dir, claudeHook("p-2", other), "Second file.").isEmpty()
        captured()*.body() == ["First file.", "Second file."]

        when: "the new file goes on"
        write(other, prompt("p-3"), answer("Third."))

        then:
        capture.turnEnded(dir, claudeHook("p-3", other), "Third.")*.body() == ["Third."]
    }

    void "a Stop for a turn already recorded, replayed after its file was cut short, records nothing again"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("A long preamble before the answer."), tool("p-1"), answer("Answered.", "u-answer"))
        capture.turnEnded(dir, claudeHook("p-1"), "Answered.")

        when: "the file is rewritten shorter, still holding the turn's final under the same identity"
        Files.writeString(claude, prompt("p-1") + answer("Answered.", "u-answer"))

        then:
        capture.turnEnded(dir, claudeHook("p-1"), "Answered.").isEmpty()
        captured()*.body() == ["A long preamble before the answer.\n\nAnswered."]
    }

    void "a new transcript for a partly recorded turn still gives up the turn's unread final"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Starting.", "u-start"))
        capture.flush(dir, claudeHook("p-1"))
        Path nextFile = dir.resolve("continued.jsonl")
        write(nextFile, prompt("p-1"), commentary("Starting.", "u-start"), answer("Finished."))

        expect: "the message already recorded is recognized by its identity, and the new one is recorded"
        capture.turnEnded(dir, claudeHook("p-1", nextFile), "Finished.")*.body() == ["Finished."]
        captured()*.body() == ["Starting.", "Finished."]
    }

    void "a stale Stop cannot skip a new turn in a file that was cut short"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("A long preamble." * 30), answer("First."))
        capture.turnEnded(dir, claudeHook("p-1"), "First.")
        Files.writeString(claude, prompt("p-2") + answer("Second."))

        when:
        capture.turnEnded(dir, claudeHook("p-1"), "First.")
        capture.turnEnded(dir, claudeHook("p-2"), "Second.")

        then:
        captured()*.body() == ["A long preamble." * 30 + "\n\nFirst.", "Second."]
    }

    void "a moved transcript gives up an earlier turn's unread final, not just the hook's own turn"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), commentary("Starting.", "u-start"))
        capture.flush(dir, claudeHook("p-1"))
        Path moved = dir.resolve("moved.jsonl")
        write(moved, prompt("p-1"), commentary("Starting.", "u-start"), answer("Finished one.", "u-final-1"),
                prompt("p-2"), answer("Finished two.", "u-final-2"))

        when:
        List<Entry> entries = capture.turnEnded(dir, claudeHook("p-2", moved), "Finished two.")

        then:
        entries*.body() == ["Finished one.", "Finished two."]
        entries*.metadata()*.causedBy() == ["prompt-1", null]
    }

    void "moving a transcript does not bring in the history its capture began after"() {
        given:
        write(claude, prompt("history"), answer("Before capture.", "u-history"),
                prompt("p-1"), answer("Participating.", "u-current"))
        capture.turnEnded(dir, claudeHook("p-1"), "Participating.")
        Path moved = dir.resolve("moved.jsonl")
        Files.copy(claude, moved)

        when:
        capture.flush(dir, claudeHook(null, moved))

        then:
        captured()*.body() == ["Participating."]
    }

    void "a capture with no cursor starts at its own turn's start, leaving earlier history out"() {
        given: "a transcript from before Sideband was installed, which no prompt hook has seen"
        write(claude, prompt("p-1"), answer("Old history."), prompt("p-2"), commentary("Now."), tool("p-2"), answer("Current."))

        expect:
        capture.turnEnded(dir, claudeHook("p-2"), "Current.")*.body() == ["Now.\n\nCurrent."]
    }

    void "a Stop whose turn start cannot be found records the payload's final, and that turn is skipped later"() {
        given: "a transcript the reader cannot place the turn in"
        write(claude, '{"type":"something-new"}\n', answer("Unplaceable."))

        when:
        List<Entry> atStop = capture.turnEnded(dir, claudeHook("p-1"), "Unplaceable.")

        then:
        atStop*.body() == ["Unplaceable."]

        when: "the next turn is read normally"
        write(claude, prompt("p-2"), answer("Placed."))

        then:
        capture.turnEnded(dir, claudeHook("p-2"), "Placed.")*.body() == ["Placed."]
        captured()*.body() == ["Unplaceable.", "Placed."]
    }

    void "a replaced or truncated transcript is read as if no capture had been made, from the turn's start"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), answer("First file."))
        capture.turnEnded(dir, claudeHook("p-1"), "First file.")

        when: "the session's transcript is now another file"
        Path other = dir.resolve("other.jsonl")
        write(other, prompt("p-2"), answer("Second file."))

        then:
        capture.turnEnded(dir, claudeHook("p-2", other), "Second file.")*.body() == ["Second file."]

        when: "that file is cut short and rewritten"
        Files.writeString(other, prompt("p-3") + answer("Rewritten."))

        then:
        capture.turnEnded(dir, claudeHook("p-3", other), "Rewritten.")*.body() == ["Rewritten."]
    }

    void "of many Stops for one turn at once, exactly one records it, with or without a readable transcript"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), answer("Once."))
        Path missing = dir.resolve("missing.jsonl")
        sessions.join(dir, new ParticipantId("claude:fable"), "s3")

        when:
        CountDownLatch start = new CountDownLatch(1)
        def pool = Executors.newFixedThreadPool(8)
        def futures = (1..8).collect { n ->
            pool.submit({
                start.await()
                n % 2 == 0 ? capture.turnEnded(dir, claudeHook("p-1"), "Once.")
                        : capture.turnEnded(dir, claudeHook("q-1", missing, "s3"), "From the payload.")
            } as Callable<List<Entry>>)
        }
        start.countDown()
        futures*.get()
        pool.shutdown()

        then:
        captured()*.body().sort() == ["From the payload.", "Once."]
    }

    void "a flush records what is there without waiting for a final"() {
        given:
        operatorPrompts(codexHook("turn-1"), "prompt-1")
        write(codex, started("turn-1"), codexSaid("Looking.", "commentary"))

        when:
        long began = System.nanoTime()
        List<Entry> entries = capture.flush(dir, codexHook("turn-1"))
        long tookMillis = (System.nanoTime() - began).intdiv(1_000_000)

        then:
        entries*.body() == ["Looking."]
        tookMillis < 300
    }

    void "words from several turns read at once become one entry per turn, in transcript order"() {
        given:
        operatorPrompts(codexHook("turn-1"), "prompt-1")
        write(codex, started("turn-1"), codexSaid("One, interrupted.", "commentary"))
        capture.prompted(dir, codexHook("turn-2"), "prompt-2")
        write(codex, started("turn-2"), codexSaid("Two begins.", "commentary"), codexSaid("Two ends.", "final_answer"))

        when:
        List<Entry> entries = capture.turnEnded(dir, codexHook("turn-2"), "Two ends.")

        then:
        entries*.body() == ["One, interrupted.", "Two begins.\n\nTwo ends."]
        entries*.metadata()*.causedBy() == ["prompt-1", "prompt-2"]
        entries*.metadata()*.from() == [Fixtures.CODEX, Fixtures.CODEX]
    }

    void "a session's receipts are kept, so nothing about a turn is forgotten"() {
        given:
        operatorPrompts(claudeHook("p-1"), "prompt-1")
        write(claude, prompt("p-1"), answer("Done."))
        capture.turnEnded(dir, claudeHook("p-1"), "Done.")

        expect:
        with(ledger.load(dir, Role.CLAUDE, "s1").turn("p-1")) {
            promptEntryId() == "prompt-1"
            mode() == CaptureLedger.Mode.TRANSCRIPT
            finalCaptured()
        }
    }
}
