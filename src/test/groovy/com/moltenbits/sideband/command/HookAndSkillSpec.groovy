package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

class HookAndSkillSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path journalFile = repo.resolve(".git/sideband/journal.md")

    def setup() {
        run("init", "--repo", repo.toString(), "--human", "james", "--skip-clients")
        stdout = new StringWriter()
    }

    int hook(String prompt, String sessionId = "s1", String cwd = repo.toString()) {
        InputStream original = System.in
        Map payload = [prompt: prompt, cwd: cwd]
        if (sessionId != null) payload.session_id = sessionId
        System.in = new ByteArrayInputStream(context.getBean(io.micronaut.serde.ObjectMapper).writeValueAsString(payload).bytes)
        try {
            return run("hook", "prompt")
        } finally {
            System.in = original
        }
    }

    void "the hook journals a prompt for the session that owns the Claude cursor and tells the model so"() {
        given:
        run("activate", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        when:
        int code = hook("@codex please look at this")

        then:
        code == ExitCode.OK
        json().hookSpecificOutput.hookEventName == "UserPromptSubmit"
        json().hookSpecificOutput.additionalContext.contains("delivered it: codex=no-session")
        Files.readString(journalFile).contains("@codex please look at this")
    }

    void "the hook stays silent and writes nothing when the session does not own the cursor, or the prompt is not a human message"() {
        given:
        run("activate", "--repo", repo.toString(), "--role", "claude", "--session-id", "s1")
        stdout = new StringWriter()

        expect:
        hook(prompt, session, plainDirectory ? TempRepo.plainDirectory().toString() : repo.toString()) == ExitCode.OK
        stdout.toString().isEmpty()
        !Files.exists(journalFile)

        where:
        prompt                          | session | plainDirectory
        "hello"                         | "other" | false
        "hello"                         | null    | false
        "[Sideband message]\n{...}"     | "s1"    | false
        "/sideband status"              | "s1"    | false
        "! sideband doctor"             | "s1"    | false
        "   "                           | "s1"    | false
        "hello"                         | "s1"    | true
    }

    void "the hook never fails the prompt, even on garbage input"() {
        given:
        InputStream original = System.in
        System.in = new ByteArrayInputStream("not json".bytes)

        expect:
        run("hook", "prompt") == ExitCode.OK
        stderr.toString().contains("unreadable payload")

        cleanup:
        System.in = original
    }

    void "skill prints the embedded instructions for the named client as plain text"() {
        when:
        int code = run("skill", "--client", "codex")

        then:
        code == ExitCode.OK
        stdout.toString() == Files.readString(Path.of("skills/sideband-codex/INSTRUCTIONS.md"))
    }
}
