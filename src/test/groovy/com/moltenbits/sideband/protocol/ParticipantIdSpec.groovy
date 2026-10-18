package com.moltenbits.sideband.protocol

import spock.lang.Specification

class ParticipantIdSpec extends Specification {

    void "agents of a project and the operator are participants"() {
        expect:
        new ParticipantId(value).isHuman() == human
        new ParticipantId(value).project() == Optional.ofNullable(project)
        new ParticipantId(value).name() == Optional.ofNullable(name)

        where:
        value                        | human | project    | name
        "sideband/claude"            | false | "sideband" | "claude"
        "sideband/astra"             | false | "sideband" | "astra"
        "listenly/review-2"          | false | "listenly" | "review-2"
        "operator"                   | true  | null       | null
        "a${"b" * 31}/a${"b" * 31}"  | false | "a" + "b" * 31 | "a" + "b" * 31
    }

    void "anything else is rejected"() {
        when:
        new ParticipantId(value)

        then:
        InvalidEntryException e = thrown()
        e.message.contains(value)

        where:
        value << ["claude", "codex", "claude:fable", "Operator", "", "a b", "sideband/", "/astra", "sideband/Astra",
                  "sideband/2nd", "sideband/-x", "sideband/a_b", "a/b/c", "Sideband/astra", "sideband/" + "a" * 33,
                  "sideband/all", "sideband/operator", "sideband/ astra", "sideband/@astra"]
    }

    void "an agent is its project and its name"() {
        expect:
        ParticipantId.of("sideband", "astra").value() == "sideband/astra"
        ParticipantId.of("sideband", "astra") != ParticipantId.of("listenly", "astra")
        ParticipantId.of("sideband", "astra").belongsTo("sideband")
        !ParticipantId.of("sideband", "astra").belongsTo("listenly")
        !ParticipantId.OPERATOR.belongsTo("sideband")
        ParticipantId.OPERATOR.value() == "operator"
    }

    void "all and operator are reserved and cannot be names"() {
        when:
        ParticipantId.of("sideband", name)

        then:
        InvalidEntryException e = thrown()
        e.message.contains("reserved")

        where:
        name << ["all", "operator"]
    }

    void "a name or project name that is not valid is refused with the rule"() {
        when:
        ParticipantId.of(project, name)

        then:
        InvalidEntryException e = thrown()
        e.message.contains("lowercase letter")

        where:
        project    | name
        "sideband" | "Fable"
        "Sideband" | "fable"
        "sideband" | null
    }

    void "an address is read from the project it is written in: #address"() {
        expect:
        ParticipantId.address(address, "sideband").value() == expected

        where:
        address            | expected
        "astra"            | "sideband/astra"
        "@astra"           | "sideband/astra"
        "listenly/astra"   | "listenly/astra"
        "listenly/@astra"  | "listenly/astra"
        "operator"         | "operator"
    }

    void "an address that names nothing is refused: #address"() {
        when:
        ParticipantId.address(address, "sideband")

        then:
        thrown(InvalidEntryException)

        where:
        address << ["", "@", "/astra", "listenly/", "a/b/c", "@all", "Astra", "listenly/@@astra", "claude:fable"]
    }

    void "a label is the name within its own project and the address elsewhere"() {
        expect:
        ParticipantId.of("sideband", "astra").label("sideband") == "astra"
        ParticipantId.of("sideband", "astra").label("listenly") == "sideband/@astra"
        ParticipantId.OPERATOR.label("sideband") == "Operator"
    }
}
