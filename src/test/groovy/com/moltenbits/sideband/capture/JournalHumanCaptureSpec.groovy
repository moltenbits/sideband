package com.moltenbits.sideband.capture

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.push.Pushes
import com.moltenbits.sideband.routing.Routing
import com.moltenbits.sideband.session.HeldPrompts
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function

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

    void "adoption links the turn of the prompt it actually consumed, even when a hold was replaced just before"() {
        given:
        HeldPrompts actual = context.getBean(HeldPrompts)
        actual.hold(state, Role.CLAUDE, "s2", "first prompt", "t-1")
        sessions.join(state, FABLE, "s2")
        HeldPrompts interleaved = Stub() {
            adopt(_, _, _, _) >> { Path dir, Role role, String session, Function draft ->
                // A prompt hook still running replaces the hold just before the join adopts it.
                actual.hold(dir, role, session, "second prompt", "t-2")
                actual.adopt(dir, role, session, draft)
            }
        }
        AgentCapture agents = context.getBean(AgentCapture)
        def subject = new JournalHumanCapture(context.getBean(Journal), context.getBean(Routing),
                context.getBean(Pushes), interleaved, sessions, agents)

        when:
        def adopted = subject.adopt(state, FABLE, "s2").get()
        def captured = agents.turnEnded(state, new AgentCapture.Hook(Role.CLAUDE, "s2", "t-2", null, null), "Answer to second prompt.")

        then:
        adopted.body() == "second prompt"
        captured.size() == 1
        captured[0].metadata().causedBy() == adopted.metadata().id()
    }
}
