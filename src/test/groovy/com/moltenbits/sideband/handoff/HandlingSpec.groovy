package com.moltenbits.sideband.handoff

import com.moltenbits.sideband.protocol.Role
import spock.lang.Specification

class HandlingSpec extends Specification {

    void "the intent sentence names the calling client's skill and nothing else"() {
        expect:
        Handling.forRole(role) == expected

        where:
        role        | expected
        Role.CLAUDE | "Sideband delivery; use the Sideband skill (/sideband) for handling instructions"
        Role.CODEX  | "Sideband delivery; use the Sideband skill (\$sideband) for handling instructions"
    }
}
