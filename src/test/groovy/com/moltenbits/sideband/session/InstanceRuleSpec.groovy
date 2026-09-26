package com.moltenbits.sideband.session

import com.moltenbits.sideband.host.HostProcess
import com.moltenbits.sideband.host.HostProcesses
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import spock.lang.Specification

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The rule of REQUIREMENTS 9.5a, on records alone: which instance a calling session is, and what that changes. */
class InstanceRuleSpec extends Specification {

    static final ParticipantId CLAUDE = ParticipantId.of(Role.CLAUDE)
    static final ParticipantId FABLE = ParticipantId.of(Role.CLAUDE, "fable")
    static final ParticipantId OLD = ParticipantId.of(Role.CLAUDE, "old")
    static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 26, 10, 0, 0, 0, ZoneOffset.ofHours(-5))
    static final HostProcess P1 = new HostProcess(100, Instant.parse("2026-09-26T15:00:00Z"))
    static final HostProcess P2 = new HostProcess(200, Instant.parse("2026-09-26T15:01:00Z"))
    static final HostProcess P3 = new HostProcess(300, Instant.parse("2026-09-26T15:02:00Z"))
    /** The same pid, handed to a later program. */
    static final HostProcess P1_REUSED = new HostProcess(100, Instant.parse("2026-09-26T16:00:00Z"))

    Set<HostProcess> running = [P1, P2, P3] as Set
    /** The machine as far as the rule sees it: what runs under each pid, and since when. */
    HostProcesses processes = { long pid -> Optional.ofNullable(running.find { it.pid() == pid }) } as HostProcesses
    InstanceRule rule = new RecordInstanceRule(processes)

    static Session at(String sessionId, HostProcess process = null, long offset = 5) {
        new Session(sessionId, T0, 3, offset, false, process)
    }

    void "the component is exposed only through its interface"() {
        given:
        ApplicationContext context = ApplicationContext.run()

        expect:
        context.getBean(InstanceRule) instanceof RecordInstanceRule

        cleanup:
        context.close()
    }

    // --- following the operator's own input (hooks) ---

    void "the record naming the calling session is the one calling, and nothing moves"() {
        given:
        Map records = [(CLAUDE): at("s1", P1), (FABLE): at("s2", P2)]

        when:
        InstanceRule.Followed followed = rule.follow(records, "s2", P2)

        then:
        followed.instance() == FABLE
        !followed.moved()
        followed.records() == records
    }

    void "a record resumed in a new process takes the process, and no other record keeps it"() {
        given: "the unnamed instance's client resumes fable's session inside its own process"
        Map records = [(CLAUDE): at("s1", P1), (FABLE): at("s2", P2)]

        when:
        InstanceRule.Followed followed = rule.follow(records, "s2", P1)

        then:
        followed.instance() == FABLE
        followed.records()[FABLE] == at("s2", P1)
        followed.records()[CLAUDE] == at("s1", null)
    }

    void "a new conversation in the same process continues that process's instance: a clear"() {
        given:
        Map records = [(CLAUDE): at("s1", P1), (FABLE): at("s2", P2)]

        when:
        InstanceRule.Followed followed = rule.follow(records, "s3", P2)

        then:
        followed.instance() == FABLE
        followed.moved()
        followed.records()[FABLE] == at("s3", P2)
        followed.records()[CLAUDE] == at("s1", P1)
    }

    void "the one instance whose client is gone is continued by a new process: a restart"() {
        given:
        running.remove(P2)
        Map records = [(CLAUDE): at("s1", P1), (FABLE): at("s2", P2)]

        when:
        InstanceRule.Followed followed = rule.follow(records, "s3", P3)

        then:
        followed.instance() == FABLE
        followed.moved()
        followed.records()[FABLE] == at("s3", P3)
        followed.records()[CLAUDE] == at("s1", P1)
    }

    void "a reused pid is not the recorded process"() {
        given: "the instance's client ended and its pid now belongs to another program"
        running = [P1_REUSED, P3] as Set
        Map records = [(CLAUDE): at("s1", P1)]

        when:
        InstanceRule.Followed followed = rule.follow(records, "s3", P3)

        then: "the record's process is not alive, so the restarted client continues it"
        followed.instance() == CLAUDE
        followed.records()[CLAUDE] == at("s3", P3)
    }

    void "a second terminal does not take an instance whose client still runs elsewhere"() {
        given:
        Map records = [(CLAUDE): at("s1", P1)]

        when:
        InstanceRule.Followed followed = rule.follow(records, "s3", P3)

        then:
        followed.instance() == null
        followed.candidates() == []
        followed.records() == records
    }

    void "a record without a process is continued while it is the only candidate, as Codex always is"() {
        expect:
        with(rule.follow([(CLAUDE): at("t1")], "t2", null)) {
            instance() == CLAUDE
            moved()
            records()[CLAUDE] == at("t2")
        }
    }

    void "several candidates cannot be told apart, and nothing moves"() {
        given:
        ParticipantId review = ParticipantId.of(Role.CODEX, "review")
        ParticipantId codex = ParticipantId.of(Role.CODEX)
        Map records = [(codex): at("t1"), (review): at("t2")]

        when:
        InstanceRule.Followed followed = rule.follow(records, "t3", null)

        then:
        followed.instance() == null
        followed.candidates() == [codex, review]
        followed.records() == records
    }

    void "nothing to follow when the role has no records"() {
        expect:
        with(rule.follow([:], "s1", P1)) {
            instance() == null
            candidates() == []
            records() == [:]
        }
    }

    // --- who a command is (the model's own commands) ---

    void "a command is the instance whose record names its session or its live process"() {
        given:
        Map records = [(CLAUDE): at("s1", P1), (FABLE): at("s2", P2)]

        expect:
        rule.identify(Role.CLAUDE, records, "s2", P3).instance() == FABLE
        rule.identify(Role.CLAUDE, records, "s9", P2).instance() == FABLE
    }

    void "a command never infers its author the way a hook continues an instance"() {
        given: "fable was taken over by another session, and a retained record names a dead process"
        running.remove(P3)
        Map records = [(CLAUDE): at("s1", P1), (FABLE): at("s2", P2), (OLD): at("s0", P3)]

        when: "the displaced session runs a command"
        InstanceRule.Identified identified = rule.identify(Role.CLAUDE, records, "s4", new HostProcess(400, Instant.EPOCH))

        then:
        identified.instance() == null
        identified.records() == [CLAUDE, FABLE, OLD]
    }

    void "a command acts as the unnamed instance only when the role has no records at all"() {
        expect:
        rule.identify(Role.CLAUDE, [:], "s1", P1).instance() == CLAUDE
        rule.identify(Role.CODEX, [:], "t1", null).instance() == ParticipantId.of(Role.CODEX)
        rule.identify(Role.CLAUDE, [(FABLE): at("s2", P2)], "s1", P1).instance() == null
    }

    // --- joining ---

    void "a join replaces the identifier's record and names the session it replaced"() {
        given:
        Map records = [(FABLE): at("s2", P2)]

        when:
        InstanceRule.Joined joined = rule.join(records, FABLE, at("s3", P3))

        then:
        joined.records() == [(FABLE): at("s3", P3)]
        joined.replaced() == "s2"
    }

    void "a join names no replaced session when it rejoins the same session or joins fresh"() {
        expect:
        rule.join([(FABLE): at("s2", P2)], FABLE, at("s2", P2)).replaced() == null
        rule.join([:], FABLE, at("s2", P2)).replaced() == null
    }

    void "a session holds one instance: joining under another identifier releases the first"() {
        given:
        Map records = [(CLAUDE): at("s1", P1)]

        when:
        InstanceRule.Joined joined = rule.join(records, FABLE, at("s1", P1))

        then:
        joined.records() == [(FABLE): at("s1", P1)]
    }

    void "a join takes its process from any other record naming it"() {
        given:
        Map records = [(CLAUDE): at("s1", P1)]

        when: "the same client resumes another conversation and joins it as fable"
        InstanceRule.Joined joined = rule.join(records, FABLE, at("s2", P1))

        then:
        joined.records() == [(CLAUDE): at("s1", null), (FABLE): at("s2", P1)]
    }
}
