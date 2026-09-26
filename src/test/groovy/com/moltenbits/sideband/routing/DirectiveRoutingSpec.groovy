package com.moltenbits.sideband.routing

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.protocol.Route
import spock.lang.Specification

class DirectiveRoutingSpec extends Specification {

    static final ParticipantId FABLE = ParticipantId.of(Role.CLAUDE, "fable")
    static final ParticipantId REVIEW = ParticipantId.of(Role.CODEX, "review")

    Routing routing = new DirectiveRouting()

    void "the first non-whitespace token selects the recipients"() {
        when:
        Destination resolution = routing.resolve(body, Fixtures.CLAUDE, [] as Set)

        then:
        resolution.to() == to
        resolution.route() == route
        resolution.directed() == directed

        where:
        body                                      | to                                 | route           | directed
        "@codex review the locking behavior."     | [Fixtures.CODEX]                   | Route.DIRECT    | true
        "@claude look at this"                    | [Fixtures.CLAUDE]                  | Route.DIRECT    | true
        "@all independently review this"          | [Fixtures.CLAUDE, Fixtures.CODEX]  | Route.BROADCAST | true
        "  \n\t@ALL leading whitespace"           | [Fixtures.CLAUDE, Fixtures.CODEX]  | Route.BROADCAST | true
        "@Codex mixed case"                       | [Fixtures.CODEX]                   | Route.DIRECT    | true
        "fix the typo"                            | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
        "What does `@all` mean?"                  | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
        "please @codex do this"                   | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
        "@all, review this"                       | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
        "@codex"                                  | [Fixtures.CODEX]                   | Route.DIRECT    | true
        "@everyone hi"                            | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
        ""                                        | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
    }

    void "a named instance is reached by its own directive, whether or not anyone has joined as it"() {
        expect:
        routing.resolve(body, Fixtures.CODEX, joined as Set).to() == to

        where:
        body                          | joined  | to
        "@claude:fable review this"   | []      | [FABLE]
        "@claude:fable review this"   | [FABLE] | [FABLE]
        "@Claude:Fable mixed case"    | []      | [FABLE]
        "@codex:review look"          | []      | [REVIEW]
    }

    void "the bare role reaches only the unnamed instance, never a named one"() {
        expect:
        routing.resolve("@claude look", Fixtures.CODEX, [Fixtures.CLAUDE, FABLE] as Set).to() == [Fixtures.CLAUDE]
    }

    void "a token that is not a valid instance is ordinary content"() {
        expect:
        with(routing.resolve(body, Fixtures.CODEX, [FABLE] as Set)) {
            to() == [Fixtures.CODEX]
            !directed()
        }

        where:
        body << ["@claude:fable! look", "@claude: look", "@claude:2nd look", "@gemini:x look", "@operator hello", "@claude:fable:x hi"]
    }

    void "@all reaches both unnamed instances and every joined named one, once each"() {
        when:
        Destination all = routing.resolve("@all look", Fixtures.CLAUDE, [FABLE, Fixtures.CODEX, REVIEW] as Set)

        then:
        all.to() == [Fixtures.CLAUDE, FABLE, Fixtures.CODEX, REVIEW]
        all.route() == Route.BROADCAST
    }

    void "an undirected message goes to the instance it was typed into"() {
        expect:
        routing.resolve("hello", Fixtures.CODEX, [] as Set).to() == [Fixtures.CODEX]
        routing.resolve("hello", FABLE, [FABLE] as Set).to() == [FABLE]
    }
}
