package com.moltenbits.sideband.routing

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.protocol.Route
import spock.lang.Specification

class DirectiveRoutingSpec extends Specification {

    Routing routing = new DirectiveRouting()

    void "the first non-whitespace token selects the recipients"() {
        when:
        Destination resolution = routing.resolve(body, Role.CLAUDE)

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

    void "an undirected message goes to the client it was typed into"() {
        expect:
        routing.resolve("hello", Role.CODEX).to() == [Fixtures.CODEX]
    }
}
