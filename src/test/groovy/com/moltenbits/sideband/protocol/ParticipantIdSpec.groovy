package com.moltenbits.sideband.protocol

import spock.lang.Specification

class ParticipantIdSpec extends Specification {

    void "client roles and human identifiers are participants"() {
        expect:
        new ParticipantId(value).isHuman() == human
        new ParticipantId(value).role() == Optional.ofNullable(role)
        new ParticipantId(value).displayName() == display

        where:
        value            | human | role        | display
        "claude"         | false | Role.CLAUDE | "Claude"
        "codex"          | false | Role.CODEX  | "Codex"
        "human:james"    | true  | null        | "James"
        "human:j.doe-2"  | true  | null        | "J.doe-2"
    }

    void "anything else is rejected"() {
        when:
        new ParticipantId(value)

        then:
        InvalidEntryException e = thrown()
        e.message.contains(value)

        where:
        value << ["Claude", "human:", "human:James", "human:-x", "gemini", "", "human:a b"]
    }

    void "factories produce the canonical strings"() {
        expect:
        ParticipantId.of(Role.CODEX).value() == "codex"
        ParticipantId.human("james").value() == "human:james"
        ParticipantId.human("james").toString() == "human:james"
    }
}
