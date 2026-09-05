package com.moltenbits.sideband.handoff

import com.moltenbits.sideband.protocol.Role
import spock.lang.Specification

class HandlingSpec extends Specification {

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
}
