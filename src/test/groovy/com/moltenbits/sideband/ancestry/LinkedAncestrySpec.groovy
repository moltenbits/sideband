package com.moltenbits.sideband.ancestry

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.protocol.Delivery
import com.moltenbits.sideband.protocol.DeliveryPolicy
import com.moltenbits.sideband.protocol.EntryMetadata
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.Route
import spock.lang.Specification

class LinkedAncestrySpec extends Specification {

    Ancestry ancestry = new LinkedAncestry()
    Map<String, EntryMetadata> store = [:]
    EntryIndex index = { String id -> Optional.ofNullable(store[id]) } as EntryIndex

    EntryMetadata human(String id) {
        put Fixtures.metadata(id: id, to: [Fixtures.CLAUDE], route: Route.DIRECT)
    }

    EntryMetadata request(String id, Map links, from = Fixtures.CLAUDE, to = Fixtures.CODEX) {
        put Fixtures.metadata([id: id, from: from, via: null, to: [to], type: MessageType.REQUEST, route: Route.DIRECT,
                               replyTo: null, causedBy: null] + links)
    }

    EntryMetadata reply(String id, String replyTo, from = Fixtures.CODEX, to = Fixtures.CLAUDE, boolean actionable = true) {
        put Fixtures.metadata(id: id, from: from, via: null, to: [to], type: MessageType.REPLY, route: Route.DIRECT,
                replyTo: replyTo, expectsReply: actionable)
    }

    EntryMetadata put(EntryMetadata m) {
        store[m.id()] = m
        m
    }

    void "human-authored entries are exempt"() {
        expect:
        ancestry.trace(human("H1"), index) instanceof Lineage.Exempt
    }

    void "agent messages addressed only to the human are exempt"() {
        given:
        def toHuman = Fixtures.metadata(id: "A1", from: Fixtures.CLAUDE, via: null, to: [Fixtures.OPERATOR],
                type: MessageType.STATUS, route: Route.DIRECT, expectsReply: true)

        expect:
        ancestry.trace(toHuman, index) instanceof Lineage.Exempt
    }

    void "non-actionable agent messages are exempt"() {
        expect:
        ancestry.trace(reply("R1", "nowhere", Fixtures.CODEX, Fixtures.CLAUDE, false), index) instanceof Lineage.Exempt
    }

    void "a delegation caused by a human entry has depth one"() {
        given:
        human("H1")

        when:
        Lineage lineage = ancestry.trace(request("A1", [causedBy: "H1"]), index)

        then:
        lineage == new Lineage.Rooted(1, "H1")
        lineage.effectiveLive(Delivery.DEFAULT) == DeliveryPolicy.AUTO
    }

    void "reply iteration follows reply_to and adds no depth"() {
        given:
        human("H1")
        request("A1", [causedBy: "H1"])
        reply("A2", "A1")
        reply("A3", "A2", Fixtures.CLAUDE, Fixtures.CODEX)
        20.times { i -> reply("B$i", i == 0 ? "A3" : "B${i - 1}", i % 2 == 0 ? Fixtures.CODEX : Fixtures.CLAUDE, i % 2 == 0 ? Fixtures.CLAUDE : Fixtures.CODEX) }

        expect:
        ancestry.trace(store["A3"], index) == new Lineage.Rooted(1, "H1")
        ancestry.trace(store["B19"], index) == new Lineage.Rooted(1, "H1")
    }

    void "caused_by takes precedence over reply_to and each link adds depth"() {
        given:
        human("H1")
        request("A1", [causedBy: "H1"])
        request("A2", [causedBy: "A1", replyTo: "A1"], Fixtures.CODEX, Fixtures.CLAUDE)
        request("A3", [causedBy: "A2"])

        expect:
        ancestry.trace(store["A3"], index) == new Lineage.Rooted(3, "H1")
    }

    void "depth beyond five forces confirmation but stays valid"() {
        given:
        human("H1")
        String previous = "H1"
        (1..6).each { i ->
            request("D$i", [causedBy: previous], i % 2 == 1 ? Fixtures.CLAUDE : Fixtures.CODEX, i % 2 == 1 ? Fixtures.CODEX : Fixtures.CLAUDE)
            previous = "D$i"
        }

        when:
        Lineage five = ancestry.trace(store["D5"], index)
        Lineage six = ancestry.trace(store["D6"], index)

        then:
        five == new Lineage.Rooted(5, "H1")
        !((Lineage.Rooted) five).exceedsDepthCap()
        five.effectiveLive(Delivery.DEFAULT) == DeliveryPolicy.AUTO
        six == new Lineage.Rooted(6, "H1")
        ((Lineage.Rooted) six).exceedsDepthCap()
        six.effectiveLive(Delivery.DEFAULT) == DeliveryPolicy.CONFIRM
    }

    void "an actionable agent message with no links is rejected"() {
        when:
        ancestry.trace(request("A1", [:]), index)

        then:
        InvalidLineageException e = thrown()
        e.message.contains("neither caused_by nor reply_to")
    }

    void "a missing ancestor is rejected"() {
        when:
        ancestry.trace(request("A1", [causedBy: "ghost"]), index)

        then:
        InvalidLineageException e = thrown()
        e.message.contains("missing ancestor ghost")
    }

    void "a chain that never reaches a human is rejected"() {
        given:
        request("A1", [causedBy: "A2"])
        request("A2", [causedBy: "A1"], Fixtures.CODEX, Fixtures.CLAUDE)

        when:
        ancestry.trace(store["A1"], index)

        then:
        InvalidLineageException e = thrown()
        e.message.contains("cycle")
    }
}
