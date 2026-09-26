package com.moltenbits.sideband.capture

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.HeldPrompts
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/** The operator's words routed among the instances that have joined (REQUIREMENTS.md 8.1, 8.2). */
class JournalHumanCaptureSpec extends Specification {

    static final ParticipantId FABLE = ParticipantId.of(Role.CLAUDE, "fable")

    /** An empty Claude registry and a home with no inbound setting, so nothing on this machine is pushed to. */
    @Shared Path claudeRegistry = Files.createTempDirectory("claude-sessions")
    @Shared Path claudeHome = Files.createTempDirectory("claude-home")
    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run(
            ["sideband.claude.sessions-directory": claudeRegistry.toString(), "sideband.home-directory": claudeHome.toString()])

    HumanCapture capture = context.getBean(HumanCapture)
    Sessions sessions = context.getBean(Sessions)
    Path state = Files.createDirectories(TempRepo.init().resolve(".git/sideband"))

    void "@all reaches every joined named instance as well as both unnamed ones"() {
        given:
        sessions.join(state, FABLE, "s2")

        expect:
        capture.capture(state, Fixtures.CLAUDE, "@all review this").metadata().to() == [Fixtures.CLAUDE, FABLE, Fixtures.CODEX]
    }

    void "a held prompt adopted at a join routes @all among the instances joined by then, the joining one included"() {
        given:
        context.getBean(HeldPrompts).hold(state, Role.CLAUDE, "s2", "@all look")
        sessions.join(state, FABLE, "s2")

        expect:
        capture.adopt(state, FABLE, "s2").get().metadata().to() == [Fixtures.CLAUDE, FABLE, Fixtures.CODEX]
    }
}
