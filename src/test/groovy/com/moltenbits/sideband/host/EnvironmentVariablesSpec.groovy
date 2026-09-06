package com.moltenbits.sideband.host

import com.moltenbits.sideband.protocol.Role
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class EnvironmentVariablesSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    void "the component is exposed only through its interface"() {
        expect:
        context.getBean(HostEnvironment) instanceof EnvironmentVariables
    }

    void "the role and session come from each client's shell markers"() {
        given:
        HostEnvironment env = new EnvironmentVariables(vars)

        expect:
        env.role() == Optional.ofNullable(role)
        role == null || env.sessionId(role) == Optional.ofNullable(session)

        where:
        vars                                                             | role        | session
        [CODEX_THREAD_ID: "01a064f7-thread"]                             | Role.CODEX  | "01a064f7-thread"
        [CLAUDECODE: "1", CLAUDE_CODE_SESSION_ID: "session_abc"]         | Role.CLAUDE | "session_abc"
        [CLAUDE_CODE_SESSION_ID: "session_abc"]                          | Role.CLAUDE | "session_abc"
        [CLAUDECODE: "1"]                                                | Role.CLAUDE | null
        [PATH: "/usr/bin"]                                               | null        | null
        [CODEX_THREAD_ID: "   "]                                         | null        | null
    }

    void "codex wins when both clients' markers are present, because its thread id is the more specific signal"() {
        expect:
        new EnvironmentVariables([CODEX_THREAD_ID: "t", CLAUDECODE: "1"]).role() == Optional.of(Role.CODEX)
    }

    void "requireRole names the overriding flag"() {
        when:
        new EnvironmentVariables([:]).requireRole("--role")

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("--role")
    }

    void "nothing about the process tree is consulted: an unmarked shell belongs to no client"() {
        expect:
        new EnvironmentVariables([:]).role().isEmpty()
        new EnvironmentVariables([:]).sessionId(Role.CODEX).isEmpty()
    }
}
