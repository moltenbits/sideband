package com.moltenbits.sideband

import com.moltenbits.sideband.protocol.Delivery
import com.moltenbits.sideband.protocol.Draft
import com.moltenbits.sideband.protocol.EntryMetadata
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.protocol.Route

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Shared sample data. The fixed clock and IDs let specifications assert exact bytes. */
class Fixtures {

    static final ParticipantId JAMES = ParticipantId.human("james")
    static final ParticipantId CLAUDE = ParticipantId.of(Role.CLAUDE)
    static final ParticipantId CODEX = ParticipantId.of(Role.CODEX)
    static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 2, 16, 42, 0, 0, ZoneOffset.ofHours(-5))
    static final Clock FIXED_CLOCK = Clock.fixed(T0.toInstant(), ZoneOffset.ofHours(-5))

    /** IDs 019a, 019b, 019c ... in order. */
    static Closure<String> sequentialIds() {
        int n = 0
        return { -> "019" + ((char) (97 + n++)) as String }
    }

    static EntryMetadata metadata(Map overrides = [:]) {
        Map m = [
                id: "019a", createdAt: T0, from: JAMES, via: Role.CLAUDE, to: [CLAUDE, CODEX],
                type: MessageType.REQUEST, route: Route.BROADCAST, replyTo: null, causedBy: null,
                expectsReply: true, heartbeatSeconds: null, delivery: Delivery.DEFAULT, bodyBytes: 58,
        ] + overrides
        new EntryMetadata(m.id, m.createdAt, m.from, m.via, m.to, m.type, m.route, m.replyTo, m.causedBy,
                m.expectsReply, m.heartbeatSeconds, m.delivery, m.bodyBytes)
    }

    static Draft humanDraft(String body = "@all independently review the proposed database migration.",
                            List<ParticipantId> to = [CLAUDE, CODEX], Role via = Role.CLAUDE) {
        Draft.humanRequest(JAMES, via, to, body)
    }

    static Draft agentDraft(Map overrides = [:]) {
        Map m = [
                from: CLAUDE, to: [CODEX], type: MessageType.REQUEST, replyTo: null, causedBy: "019a",
                expectsReply: true, heartbeatSeconds: null, delivery: Delivery.DEFAULT,
                body: "independently test the concurrency behavior",
        ] + overrides
        new Draft(m.from, null, m.to, m.type, Route.forRecipients(m.to), m.replyTo, m.causedBy,
                m.expectsReply, m.heartbeatSeconds, m.delivery, m.body)
    }
}
