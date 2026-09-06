package com.moltenbits.sideband.protocol

import spock.lang.Specification

class ParticipantIdSpec extends Specification {

    void "client roles and the operator are participants"() {
        expect:
        new ParticipantId(value).isHuman() == human
        new ParticipantId(value).role() == Optional.ofNullable(role)
        new ParticipantId(value).displayName() == display

        where:
        value      | human | role        | display
        "claude"   | false | Role.CLAUDE | "Claude"
        "codex"    | false | Role.CODEX  | "Codex"
        "operator" | true  | null        | "Operator"
    }

    void "anything else is rejected"() {
        when:
        new ParticipantId(value)

        then:
        InvalidEntryException e = thrown()
        e.message.contains(value)

        where:
        value << ["Claude", "human:james", "Operator", "gemini", "", "a b"]
    }

    void "factories produce the canonical strings"() {
        expect:
        ParticipantId.of(Role.CODEX).value() == "codex"
        ParticipantId.OPERATOR.value() == "operator"
        ParticipantId.OPERATOR.toString() == "operator"
    }
}
