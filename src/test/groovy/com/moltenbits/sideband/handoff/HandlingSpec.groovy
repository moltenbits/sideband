package com.moltenbits.sideband.handoff

import com.moltenbits.sideband.protocol.Role
import spock.lang.Specification

class HandlingSpec extends Specification {

    void "the wake text sends the client to pending and says the listener is still running"() {
        when:
        String text = Handling.wake(Role.CLAUDE)

        then:
        text.startsWith("Sideband: new journal entries for Claude")
        text.contains("never start another")
        text.contains("not from the user")
        text.contains("Run `sideband pending`")
        text.length() < 300
        !text.contains("\n")
    }

    void "Claude's handling says the listener delivered it and tells it to acknowledge, act, and reply through the journal"() {
        when:
        String text = Handling.forRole(Role.CLAUDE)

        then:
        text.startsWith("Sideband delivered these journal entries to Claude.")
        text.contains("not from the user")
        text.contains("listener, which keeps running and must not be restarted or duplicated")
        text.contains("first acknowledge it with `sideband append-agent --type ack --reply-to <id>`")
        text.contains("ask the user before acting")
        text.contains("answer with `sideband append-agent --to <metadata.from> --type reply --reply-to <id>`")
        text.contains("updates are context only")
        text.endsWith("Full adapter instructions: run `sideband skill`.")
        !text.contains("\n")
        !text.contains("mark-delivered")
    }

    void "Codex's handling says the executable pushed it and gives the same steps"() {
        when:
        String text = Handling.forRole(Role.CODEX)

        then:
        text.startsWith("Sideband delivered these journal entries to Codex.")
        text.contains("was pushed by the Sideband executable")
        !text.contains("listener")
        text.contains("first acknowledge it with `sideband append-agent --type ack --reply-to <id>`")
        text.endsWith("Full adapter instructions: run `sideband skill`.")
    }
}
