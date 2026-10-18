package com.moltenbits.sideband.capture

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.project.ProjectLocation
import com.moltenbits.sideband.project.Projects
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

    static final ParticipantId FABLE = ParticipantId.of("repo", "fable")

    /** An empty Claude registry and a home with no inbound setting, so nothing on this machine is pushed to. */
    @Shared Path claudeRegistry = Files.createTempDirectory("claude-sessions")
    @Shared Path claudeHome = Files.createTempDirectory("claude-home")
    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run(
            ["sideband.claude.sessions-directory": claudeRegistry.toString(), "sideband.home-directory": claudeHome.toString()])

    HumanCapture capture = context.getBean(HumanCapture)
    Sessions sessions = context.getBean(Sessions)
    Projects projects = context.getBean(Projects)
    Path state = Files.createDirectories(TempRepo.init().resolve(".git/sideband"))

    void "@all reaches every participant joined in the project, and nobody in another"() {
        given:
        sessions.join(state, Fixtures.CLAUDE, Role.CLAUDE, "s1")
        sessions.join(state, FABLE, Role.CLAUDE, "s2")
        sessions.join(state, Fixtures.CODEX, Role.CODEX, "t1")
        sessions.join(state, ParticipantId.of("listenly", "opus"), Role.CLAUDE, "s3")

        expect:
        capture.capture(state, Fixtures.CLAUDE, "@all review this").metadata().to() == [Fixtures.CLAUDE, Fixtures.CODEX, FABLE]
    }

    void "a held prompt adopted at a join routes @all among the instances joined by then, the joining one included"() {
        given:
        context.getBean(HeldPrompts).hold(state, "repo", Role.CLAUDE, "s2", "@all look")
        sessions.join(state, FABLE, Role.CLAUDE, "s2")

        expect:
        capture.adopt(state, FABLE, Role.CLAUDE, "s2").get().metadata().to() == [FABLE]
    }

    void "words addressed to another registered project go there"() {
        given:
        projects.register(state, new ProjectLocation(Path.of("/work/listenly/.git"), Path.of("/work/listenly")), null)

        when:
        Captured captured = capture.capture(state, Fixtures.CLAUDE, "listenly/@opus look at the schema")

        then:
        captured.metadata().to() == [ParticipantId.of("listenly", "opus")]
        captured.unrouted() == null
    }

    void "words addressed to a project nobody registered are recorded for the instance typed into, and the failed route is said"() {
        when:
        Captured captured = capture.capture(state, Fixtures.CLAUDE, "nowhere/@opus look at the schema")

        then:
        captured.metadata().to() == [Fixtures.CLAUDE]
        captured.unrouted() == "nowhere"
        captured.pushes() == []
        context.getBean(Journal).find(state, captured.metadata().id()).isPresent()
    }

    void "an adopted prompt addressed to a project nobody registered says so too"() {
        given:
        context.getBean(HeldPrompts).hold(state, "repo", Role.CLAUDE, "s2", "nowhere/@opus look")
        sessions.join(state, FABLE, Role.CLAUDE, "s2")

        when:
        Captured adopted = capture.adopt(state, FABLE, Role.CLAUDE, "s2").get()

        then:
        adopted.metadata().to() == [FABLE]
        adopted.unrouted() == "nowhere"
    }

    void "a capture that names nobody, @all before anyone joined, is refused and writes nothing"() {
        when:
        capture.capture(state, Fixtures.CLAUDE, "@all hello")

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("no joined participants")
        context.getBean(Journal).end(state) == 0
    }

    void "adoption links the turn of the prompt it actually consumed, even when a hold was replaced just before"() {
        given:
        HeldPrompts actual = context.getBean(HeldPrompts)
        actual.hold(state, "repo", Role.CLAUDE, "s2", "first prompt", "t-1")
        sessions.join(state, FABLE, Role.CLAUDE, "s2")
        HeldPrompts interleaved = Stub() {
            adopt(_, _, _, _, _) >> { Path dir, String project, Role role, String session, Function draft ->
                // A prompt hook still running replaces the hold just before the join adopts it.
                actual.hold(dir, project, role, session, "second prompt", "t-2")
                actual.adopt(dir, project, role, session, draft)
            }
        }
        AgentCapture agents = context.getBean(AgentCapture)
        def subject = new JournalHumanCapture(context.getBean(Journal), context.getBean(Routing),
                context.getBean(Pushes), interleaved, sessions, projects, agents)

        when:
        def adopted = subject.adopt(state, FABLE, Role.CLAUDE, "s2").get()
        def captured = agents.turnEnded(state, new AgentCapture.Hook("repo", Role.CLAUDE, "s2", "t-2", null, null), "Answer to second prompt.")

        then:
        adopted.body() == "second prompt"
        captured.size() == 1
        captured[0].metadata().causedBy() == adopted.metadata().id()
    }
}
