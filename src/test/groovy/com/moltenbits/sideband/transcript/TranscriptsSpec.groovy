package com.moltenbits.sideband.transcript

import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class TranscriptsSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Transcripts transcripts = context.getBean(Transcripts)
    Path dir = Files.createTempDirectory("transcripts")

    Path fixture(String name) {
        Path copy = dir.resolve(name)
        Files.write(copy, getClass().getResourceAsStream("/transcripts/" + name).bytes)
        copy
    }

    void "the component is exposed only through its interface"() {
        expect:
        !java.lang.reflect.Modifier.isPublic(transcripts.getClass().modifiers)
    }

    void "a Claude Code transcript yields only the assistant's visible text, in order, under the turn each was said in"() {
        given:
        Path file = fixture("claude-code-2.1.296.jsonl")

        when:
        Excerpt excerpt = transcripts.read(Role.CLAUDE, file, 0, null)

        then: "thinking, tool calls, tool output, subagents, synthetic notices and API errors are left out"
        excerpt.messages()*.text() == ["I'll list the directory first.", "Only `.git` is here.", "Codex says the review is clean."]
        excerpt.messages()*.turn() == ["p-1", "p-1", "p-2"]

        and: "the turn's final message is the one the host marks as ending it"
        excerpt.messages()*.endsTurn() == [false, true, true]

        and: "each keeps the identity the host gave its record"
        excerpt.messages()*.id() == ["a2", "a6", "a7"]

        and: "everything was consumed, and the reader ends inside the last turn"
        excerpt.end() == Files.size(file)
        excerpt.turn() == "p-2"
    }

    void "a Codex rollout yields commentary and final answers from response items only"() {
        given:
        Path file = fixture("codex-0.162.1.jsonl")

        when:
        Excerpt excerpt = transcripts.read(Role.CODEX, file, 0, null)

        then: "the event_msg copies, reasoning, user and developer messages, and tool calls are left out"
        excerpt.messages()*.text() == ["I'll list the directory first.", "Only `.git` is here.", "Claude's review is clean."]
        excerpt.messages()*.turn() == ["turn-1", "turn-1", "turn-2"]
        excerpt.messages()*.endsTurn() == [false, true, true]
        excerpt.messages()*.id() == ["msg_c1", "msg_f1", "msg_f2"]
        excerpt.turn() == "turn-2"
    }

    void "each message carries the byte position of the line it came from"() {
        given:
        Path file = fixture("claude-code-2.1.296.jsonl")
        List<String> lines = Files.readAllLines(file)

        when:
        Excerpt excerpt = transcripts.read(Role.CLAUDE, file, 0, null)

        then:
        excerpt.messages()*.position() == excerpt.messages().collect { message ->
            int index = lines.findIndexOf { it.contains('"' + message.text() + '"') }
            lines.take(index).sum(0L) { it.getBytes("UTF-8").length + 1L }
        }
    }

    void "reading from an offset continues inside the turn the reader was in"() {
        given:
        Path file = fixture("codex-0.162.1.jsonl")
        Excerpt first = transcripts.read(Role.CODEX, file, 0, null)
        long afterCommentary = first.messages()[1].position()

        when: "the reader resumes at the final answer's line, past the turn's start record"
        Excerpt rest = transcripts.read(Role.CODEX, file, afterCommentary, "turn-1")

        then:
        rest.messages()*.text() == ["Only `.git` is here.", "Claude's review is clean."]
        rest.messages()*.turn() == ["turn-1", "turn-2"]
    }

    void "a line still being written is left for the next read, never read in part"() {
        given:
        Path file = dir.resolve("partial.jsonl")
        String complete = '{"type":"event_msg","payload":{"type":"task_started","turn_id":"turn-1"}}\n'
        String message = '{"type":"response_item","payload":{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Done."}]}}'
        Files.writeString(file, complete + message.substring(0, 40))

        when:
        Excerpt partial = transcripts.read(Role.CODEX, file, 0, null)

        then:
        partial.messages().isEmpty()
        partial.end() == complete.getBytes("UTF-8").length
        partial.turn() == "turn-1"
        transcripts.end(file) == partial.end()

        when: "the host finishes the line"
        Files.writeString(file, complete + message + "\n")
        Excerpt finished = transcripts.read(Role.CODEX, file, partial.end(), partial.turn())

        then:
        finished.messages()*.text() == ["Done."]
        finished.messages()*.turn() == ["turn-1"]
        finished.end() == Files.size(file)
    }

    void "offsets count bytes, so a read resumes exactly after multibyte text"() {
        given:
        Path file = dir.resolve("multibyte.jsonl")
        String first = '{"type":"response_item","payload":{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"Résumé → naïve 日本語 ✓"}]}}\n'
        String second = '{"type":"response_item","payload":{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Done."}]}}\n'
        Files.writeString(file, first)

        when:
        Excerpt before = transcripts.read(Role.CODEX, file, 0, "turn-1")

        then:
        before.messages()*.text() == ["Résumé → naïve 日本語 ✓"]
        before.end() == first.getBytes("UTF-8").length

        when:
        Files.writeString(file, first + second)
        Excerpt after = transcripts.read(Role.CODEX, file, before.end(), before.turn())

        then:
        after.messages()*.text() == ["Done."]
        after.messages()[0].position() == before.end()
    }

    void "a complete record without its newline is still being written, so it is not read"() {
        given:
        Path file = dir.resolve("unterminated.jsonl")
        Files.writeString(file, '{"type":"response_item","payload":{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Done."}]}}')

        when:
        Excerpt excerpt = transcripts.read(Role.CODEX, file, 0, "turn-1")

        then:
        excerpt.messages().isEmpty()
        excerpt.end() == 0
        transcripts.end(file) == 0
    }

    void "nothing after the offset reads as nothing, at the same offset"() {
        given:
        Path file = fixture("claude-code-2.1.296.jsonl")

        when:
        Excerpt excerpt = transcripts.read(Role.CLAUDE, file, Files.size(file), "p-2")

        then:
        excerpt.messages().isEmpty()
        excerpt.end() == Files.size(file)
        excerpt.turn() == "p-2"
    }

    void "every turn a transcript starts is listed in order, by the identity the host gives it, at its first record"() {
        given:
        Path claude = fixture("claude-code-2.1.296.jsonl")
        Path codex = fixture("codex-0.162.1.jsonl")

        when:
        Map<String, Long> claudeStarts = transcripts.starts(Role.CLAUDE, claude)
        Map<String, Long> codexStarts = transcripts.starts(Role.CODEX, codex)

        then: "a later record of the same turn, such as a tool result, does not move its start"
        claudeStarts.keySet().toList() == ["p-1", "p-2"]
        codexStarts.keySet().toList() == ["turn-1", "turn-2"]
        transcripts.read(Role.CLAUDE, claude, claudeStarts["p-1"], null).messages()*.text()[0] == "I'll list the directory first."
        transcripts.read(Role.CLAUDE, claude, claudeStarts["p-2"], null).messages()*.text() == ["Codex says the review is clean."]
        transcripts.read(Role.CODEX, codex, codexStarts["turn-2"], null).messages()*.text() == ["Claude's review is clean."]
    }

    void "the end of a long transcript is the end of its last complete line"() {
        given: "lines longer than the reader's window, and a last line still being written"
        Path file = dir.resolve("long.jsonl")
        String line = '{"type":"x","pad":"' + ("a" * 20000) + '"}\n'
        Files.writeString(file, line * 3 + '{"type":"partial"')

        expect:
        transcripts.end(file) == line.getBytes("UTF-8").length * 3L

        when: "the file holds no complete line, or nothing at all"
        Files.writeString(file, '{"type":"partial"')
        Path empty = Files.createFile(dir.resolve("empty.jsonl"))

        then:
        transcripts.end(file) == 0
        transcripts.end(empty) == 0
    }

    void "a transcript that does not exist is an I/O failure, not an empty read"() {
        when:
        transcripts.read(Role.CLAUDE, dir.resolve("missing.jsonl"), 0, null)

        then:
        thrown(NoSuchFileException)
    }
}
