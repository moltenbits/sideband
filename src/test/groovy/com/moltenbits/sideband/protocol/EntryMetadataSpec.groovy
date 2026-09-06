package com.moltenbits.sideband.protocol

import com.moltenbits.sideband.Fixtures
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.time.OffsetDateTime

class EntryMetadataSpec extends Specification {

    static final String SAMPLE = '{"id":"019a","created_at":"2026-09-02T16:42:00-05:00","from":"human:james","via":"claude",' +
            '"to":["claude","codex"],"type":"request","route":"broadcast","reply_to":null,"caused_by":null,' +
            '"expects_reply":true,"delivery":{"live":"auto","backlog":"confirm"},"body_bytes":58}'

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    ObjectMapper json = context.getBean(ObjectMapper)

    void "serializes exactly as the requirements example, in field order"() {
        expect:
        json.writeValueAsString(Fixtures.metadata()) == SAMPLE
    }

    void "round-trips through JSON"() {
        expect:
        json.readValue(SAMPLE, EntryMetadata) == Fixtures.metadata()
    }

    void "unknown fields are ignored on read"() {
        given:
        String extended = SAMPLE.replace('"body_bytes":58}', '"body_bytes":58,"future_field":{"x":1}}')

        expect:
        json.readValue(extended, EntryMetadata) == Fixtures.metadata()
    }

    void "entries written under the former instruction type read as requests"() {
        expect:
        json.readValue(SAMPLE.replace('"type":"request"', '"type":"instruction"'), EntryMetadata) == Fixtures.metadata()
        json.writeValueAsString(json.readValue(SAMPLE.replace('"type":"request"', '"type":"instruction"'), EntryMetadata)) == SAMPLE
    }

    void "an unknown enum value is invalid rather than guessed"() {
        when:
        json.readValue(SAMPLE.replace('"type":"request"', '"type":"control"'), EntryMetadata)

        then:
        thrown(IOException)
    }

    void "agent entries carry no via and nullable links serialize as null"() {
        given:
        EntryMetadata reply = Fixtures.metadata(id: "019c", from: Fixtures.CODEX, via: null, to: [Fixtures.CLAUDE],
                type: MessageType.REPLY, route: Route.DIRECT, replyTo: "019b", expectsReply: false, bodyBytes: 3)

        expect:
        json.writeValueAsString(reply).contains('"from":"codex","via":null,"to":["claude"],"type":"reply","route":"direct","reply_to":"019b","caused_by":null,"expects_reply":false')
    }

    void "structural rules are enforced on construction"() {
        when:
        Fixtures.metadata(overrides)

        then:
        InvalidEntryException e = thrown()
        e.message.contains(fragment)

        where:
        overrides                                                                          | fragment
        [to: []]                                                                           | "at least one recipient"
        [to: [Fixtures.CODEX, Fixtures.CODEX]]                                             | "repeat"
        [via: null]                                                                        | "'via' is required"
        [to: [Fixtures.CODEX], route: Route.BROADCAST]                                     | "must be direct"
        [route: Route.DIRECT]                                                              | "must be broadcast"
        [from: Fixtures.CLAUDE, via: null, type: MessageType.REPLY, replyTo: null]         | "reply must set"
        [id: ""]                                                                           | "'id' must not be blank"
        [id: "a b"]                                                                        | "whitespace"
        [bodyBytes: -1]                                                                    | "negative"
        [from: Fixtures.CLAUDE, via: null, type: MessageType.REQUEST, replyTo: " "]        | "'reply_to' must not be blank"
    }

    void "drafts apply the same rules and reject a blank body"() {
        when:
        Fixtures.agentDraft(body: "  \n")

        then:
        thrown(InvalidEntryException)

        when:
        Fixtures.agentDraft(type: MessageType.REPLY, replyTo: null)

        then:
        thrown(InvalidEntryException)
    }

    void "classification helpers"() {
        expect:
        Fixtures.metadata().isAgentAuthored() == false
        Fixtures.metadata().addressesAnyClient()
        !Fixtures.metadata(from: Fixtures.CLAUDE, via: null, to: [Fixtures.JAMES], type: MessageType.STATUS, route: Route.DIRECT).addressesAnyClient()
        Fixtures.metadata().addresses(Fixtures.CODEX)
        !Fixtures.metadata().addresses(Fixtures.JAMES)
    }

    void "timestamps keep their offset"() {
        given:
        EntryMetadata utc = Fixtures.metadata(createdAt: OffsetDateTime.parse("2026-09-05T03:29:33Z"))

        expect:
        json.writeValueAsString(utc).contains('"created_at":"2026-09-05T03:29:33Z"')
    }
}
