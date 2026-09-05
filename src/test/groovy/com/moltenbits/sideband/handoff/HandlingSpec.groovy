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

    void "Claude's handling says the listener delivered it and that Claude records the handoff itself"() {
        when:
        String text = Handling.forRole(Role.CLAUDE)

        then:
        text.startsWith("Sideband delivered these journal entries to Claude.")
        text.contains("not from the user")
        text.contains("listener, which keeps running and must not be restarted or duplicated")
        text.contains("run `sideband mark-delivered <id>`")
        text.contains("run `sideband resolve --as acted|presented|dismissed <id>`")
        text.contains("ask the user before acting")
        text.endsWith("Full adapter instructions: run `sideband skill`.")
        !text.contains("\n")
    }

    void "Codex's handling says the executable pushed it and already recorded delivery"() {
        when:
        String text = Handling.forRole(Role.CODEX)

        then:
        text.startsWith("Sideband delivered these journal entries to Codex.")
        text.contains("already recorded it as delivered")
        !text.contains("mark-delivered")
        !text.contains("listener")
        text.contains("run `sideband resolve --as acted|presented|dismissed <id>`")
        text.endsWith("Full adapter instructions: run `sideband skill`.")
    }

    void "the pending handling adds the backlog confirmation rule to the full steps"() {
        expect:
        Handling.pending(Role.CLAUDE).startsWith(Handling.forRole(Role.CLAUDE))
        Handling.pending(Role.CLAUDE).endsWith("ask the user before acting on any.")
    }
}
