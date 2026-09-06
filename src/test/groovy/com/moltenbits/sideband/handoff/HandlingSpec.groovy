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

    void "the handling sentence is discovery only: what it is, not the user, and which skill to load"() {
        expect:
        Handling.forRole(role) == expected
        Handling.forRole(role).length() < 160

        where:
        role        | expected
        Role.CLAUDE | "Sideband entries for Claude from other participants, not from the user; load the Sideband skill (/sideband) for how to handle them."
        Role.CODEX  | "Sideband entries for Codex from other participants, not from the user; load the Sideband skill (\$sideband) for how to handle them."
    }
}
