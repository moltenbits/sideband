package com.moltenbits.sideband.protocol

import spock.lang.Specification

class ParticipantIdSpec extends Specification {

    void "client instances and the operator are participants"() {
        expect:
        new ParticipantId(value).isHuman() == human
        new ParticipantId(value).role() == Optional.ofNullable(role)
        new ParticipantId(value).name() == Optional.ofNullable(name)
        new ParticipantId(value).displayName() == display

        where:
        value               | human | role        | name        | display
        "claude"            | false | Role.CLAUDE | null        | "Claude"
        "codex"             | false | Role.CODEX  | null        | "Codex"
        "operator"          | true  | null        | null        | "Operator"
        "claude:fable"      | false | Role.CLAUDE | "fable"     | "Claude (fable)"
        "codex:review-2"    | false | Role.CODEX  | "review-2"  | "Codex (review-2)"
        "claude:a" + "b" * 31 | false | Role.CLAUDE | "a" + "b" * 31 | "Claude (a${"b" * 31})"
    }

    void "anything else is rejected"() {
        when:
        new ParticipantId(value)

        then:
        InvalidEntryException e = thrown()
        e.message.contains(value)

        where:
        value << ["Claude", "human:james", "Operator", "gemini", "", "a b",
                  "claude:", "claude:Fable", "claude:2nd", "claude:-x", "claude:a_b", "claude:fable:x",
                  "operator:james", "gemini:x", ":fable", "claude:" + "a" * 33, "claude: fable"]
    }

    void "factories produce the canonical strings"() {
        expect:
        ParticipantId.of(Role.CODEX).value() == "codex"
        ParticipantId.of(Role.CLAUDE, "fable").value() == "claude:fable"
        ParticipantId.OPERATOR.value() == "operator"
        ParticipantId.OPERATOR.toString() == "operator"
    }

    void "a named instance is not its role's unnamed instance"() {
        expect:
        ParticipantId.of(Role.CLAUDE, "fable") != ParticipantId.of(Role.CLAUDE)
        !ParticipantId.of(Role.CLAUDE, "fable").isUnnamed()
        ParticipantId.of(Role.CLAUDE).isUnnamed()
        !ParticipantId.OPERATOR.isUnnamed()
    }

    void "the named factory refuses a missing name rather than naming the instance null"() {
        when:
        ParticipantId.of(Role.CLAUDE, null)

        then:
        NullPointerException e = thrown()
        e.message == "name"

        and: "a name that happens to be spelled null is still a name"
        ParticipantId.of(Role.CLAUDE, "null").name() == Optional.of("null")
    }

    void "a name that is not valid is refused by the factory with the rule"() {
        when:
        ParticipantId.of(Role.CLAUDE, "Fable")

        then:
        InvalidEntryException e = thrown()
        e.message.contains("'claude:Fable'")
        e.message.contains("lowercase letter")
    }
}
