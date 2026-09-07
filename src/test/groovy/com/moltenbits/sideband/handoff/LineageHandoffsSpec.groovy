package com.moltenbits.sideband.handoff

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Entry
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.DeliveryPolicy
import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class LineageHandoffsSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Journal journal = context.getBean(Journal)
    Handoffs handoffs = context.getBean(Handoffs)
    Path dir = Files.createTempDirectory("handoff")

    void "a rooted request keeps its live policy and an orphan is downgraded to confirm with the reason"() {
        given:
        Entry h = journal.append(dir, Fixtures.humanDraft("@claude go", [Fixtures.CLAUDE]))
        Entry rooted = journal.append(dir, Fixtures.agentDraft(causedBy: h.metadata().id()))
        Entry orphan = journal.append(dir, Fixtures.agentDraft(causedBy: "ghost"))

        when:
        List<Handoff> prepared = handoffs.prepare(dir, [rooted, orphan])

        then:
        prepared[0].effectiveLive() == DeliveryPolicy.AUTO
        prepared[0].lineageProblem() == null
        prepared[1].effectiveLive() == DeliveryPolicy.CONFIRM
        prepared[1].lineageProblem().contains("missing ancestor ghost")
    }

    void "the envelope is the marker, a newline, and the batch as one JSON line"() {
        given:
        Entry h = journal.append(dir, Fixtures.humanDraft("@codex hi", [Fixtures.CODEX]))
        Batch batch = Batch.forRole(Role.CODEX, h.seq(), h.seq(), handoffs.prepare(dir, [h]), false)

        when:
        String envelope = handoffs.envelope(batch)

        then:
        envelope.startsWith(Handoffs.ENVELOPE_MARKER + "\n{\"intent\":\"Sideband delivery; use the Sideband skill (\$sideband) for handling instructions\",\"start\":")
        envelope.count("\n") == 1
        envelope.contains('"entries":[{"metadata":{"id":"' + h.metadata().id() + '"')
        envelope.endsWith('"timed_out":false}')
    }

    void "preparing nothing reads nothing"() {
        expect:
        handoffs.prepare(dir, []) == []
    }
}
