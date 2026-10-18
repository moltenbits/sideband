package com.moltenbits.sideband.routing

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Route
import spock.lang.Specification

import java.util.function.Predicate

class DirectiveRoutingSpec extends Specification {

    static final ParticipantId FABLE = ParticipantId.of("repo", "fable")
    static final ParticipantId REVIEW = ParticipantId.of("repo", "review")
    static final Predicate<String> REGISTERED = { String name -> name in ["repo", "listenly"] }

    Routing routing = new DirectiveRouting()

    Destination resolve(String body, ParticipantId via, Collection<ParticipantId> joined = []) {
        routing.resolve(body, via, joined as Set, REGISTERED)
    }

    void "the first non-whitespace token selects the recipients"() {
        when:
        Destination resolution = resolve(body, Fixtures.CLAUDE, [Fixtures.CLAUDE, Fixtures.CODEX])

        then:
        resolution.to() == to
        resolution.route() == route
        resolution.directed() == directed
        resolution.unrouted() == null

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
        ""                                        | [Fixtures.CLAUDE]                  | Route.DIRECT    | false
    }

    void "any name is reached by its own directive in the project typed in, whether or not anyone has joined as it"() {
        expect:
        resolve(body, Fixtures.CODEX, joined).to() == to

        where:
        body                       | joined  | to
        "@fable review this"       | []      | [FABLE]
        "@fable review this"       | [FABLE] | [FABLE]
        "@Fable mixed case"        | []      | [FABLE]
        "@review look"             | []      | [REVIEW]
        "@everyone hi"             | []      | [ParticipantId.of("repo", "everyone")]
    }

    void "project/@name reaches that name in another registered project"() {
        when:
        Destination resolution = resolve("listenly/@opus look at the schema", Fixtures.CLAUDE)

        then:
        resolution.to() == [ParticipantId.of("listenly", "opus")]
        resolution.directed()
        resolution.unrouted() == null

        and: "naming the project typed in is the same as leaving it out"
        resolve("repo/@fable hi", Fixtures.CLAUDE).to() == [FABLE]
    }

    void "a directive to a project nobody registered goes to the instance typed into, and says the route failed"() {
        when:
        Destination resolution = resolve("nowhere/@opus look", Fixtures.CLAUDE)

        then:
        resolution.to() == [Fixtures.CLAUDE]
        !resolution.directed()
        resolution.unrouted() == "nowhere"
    }

    void "a token that names nothing is ordinary content"() {
        expect:
        with(resolve(body, Fixtures.CODEX, [FABLE])) {
            to() == [Fixtures.CODEX]
            !directed()
            unrouted() == null
        }

        where:
        body << ["@fable! look", "@ look", "@2nd look", "@operator hello", "@claude:fable hi", "listenly/opus hi",
                 "Listenly/@ hi", "/@opus hi", "a/b/@c hi", "listenly/@@opus hi", "2bad/@opus hi"]
    }

    void "@all reaches every participant joined in the project typed in, once each, and nobody elsewhere"() {
        when:
        Destination all = resolve("@all look", Fixtures.CLAUDE,
                [FABLE, Fixtures.CODEX, REVIEW, Fixtures.CLAUDE, ParticipantId.of("listenly", "opus")])

        then:
        all.to() == [Fixtures.CLAUDE, Fixtures.CODEX, FABLE, REVIEW]
        all.route() == Route.BROADCAST
    }

    void "@all with nobody joined in the project is refused"() {
        when:
        resolve("@all look", Fixtures.CLAUDE, [ParticipantId.of("listenly", "opus")])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("no joined participants in this project")
    }

    void "an undirected message goes to the instance it was typed into"() {
        expect:
        resolve("hello", Fixtures.CODEX).to() == [Fixtures.CODEX]
        resolve("hello", FABLE, [FABLE]).to() == [FABLE]
    }
}
