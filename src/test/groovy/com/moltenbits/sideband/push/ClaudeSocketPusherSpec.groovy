package com.moltenbits.sideband.push

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.home.SidebandHome
import com.moltenbits.sideband.protocol.Role
import com.moltenbits.sideband.session.Delivery
import com.moltenbits.sideband.session.Sessions
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import static java.nio.charset.StandardCharsets.UTF_8

/** Runs the real Claude pusher against a fake Claude Code session registry and a fake inbox socket. */
class ClaudeSocketPusherSpec extends Specification {

    @Shared Path registry = Files.createTempDirectory("claude-sessions")
    /** A fake user home whose settings accept cross-session messages, so pushes are attempted. */
    @Shared Path fakeHome = accepting(Files.createTempDirectory("claude-home"))
    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run(
            ["sideband.claude.sessions-directory": registry.toString(),
             "sideband.home-directory": fakeHome.toString()])

    static Path accepting(Path home) {
        Files.createDirectories(home.resolve(".claude"))
        Files.writeString(home.resolve(".claude/settings.json"), '{"crossSessionInbound": "accept"}')
        home
    }

    HostPusher pusher = context.getBeansOfType(HostPusher).find { it.role() == Role.CLAUDE }
    SidebandHome home = context.getBean(SidebandHome)
    Path repo = TempRepo.init()
    Path state = Files.createDirectories(home.locate(repo))
    List<Path> sockets = []
    List<ServerSocketChannel> servers = []

    def setup() {
        Files.list(registry).each { Files.delete(it) }
    }

    def cleanup() {
        servers.each { it.close() }
        sockets.each { Files.deleteIfExists(it) }
    }

    /** macOS caps socket paths at 104 bytes, so they live directly under /tmp. */
    Path socketPath() {
        Path path = Path.of("/tmp/sideband-test-" + UUID.randomUUID().toString().substring(0, 8) + ".sock")
        sockets << path
        path
    }

    /** A fake inbox: accepts one connection and returns everything the client wrote. */
    CompletableFuture<String> inbox(Path socket) {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        servers << server
        CompletableFuture.supplyAsync {
            SocketChannel client = server.accept()
            ByteArrayOutputStream received = new ByteArrayOutputStream()
            ByteBuffer buffer = ByteBuffer.allocate(8192)
            while (client.read(buffer) >= 0) {
                buffer.flip()
                received.write(buffer.array(), 0, buffer.limit())
                buffer.clear()
            }
            client.close()
            new String(received.toByteArray(), UTF_8)
        }
    }

    void register(long pid, Path cwd, Path socket, long startedAt = 1000L, String name = "session-" + pid, String sessionId = UUID.randomUUID().toString()) {
        Files.writeString(registry.resolve(pid + ".json"), context.getBean(ObjectMapper).writeValueAsString([
                pid: pid, sessionId: sessionId, cwd: cwd.toString(), startedAt: startedAt,
                version: "2.1.263", peerProtocol: 1, kind: "interactive", entrypoint: "cli",
                messagingSocketPath: socket.toString(), name: name, status: "idle"]))
    }

    void "the component is exposed only through its interface"() {
        expect:
        pusher instanceof ClaudeSocketPusher
    }

    void "the envelope is posted to the registered session's inbox as one user frame"() {
        given:
        Path socket = socketPath()
        def received = inbox(socket)
        register(4242, repo, socket)
        String text = "[Sideband message]\n{\"intent\":\"Sideband delivery\",\"entries\":[]}"

        when:
        PushResult result = pusher.push(state, text)
        String wire = received.get()

        then:
        result.role() == Role.CLAUDE
        result.outcome() == PushOutcome.PUSHED
        result.detail().contains("session-4242")
        result.detail().contains("4242")
        wire.endsWith("\n")
        wire.count("\n") == 1
        Map frame = context.getBean(ObjectMapper).readValue(wire, Map)
        frame.type == "user"
        frame.message.role == "user"
        frame.message.content == text
    }

    void "no registered session for this repository leaves the entry for backlog"() {
        given: "a session in another repository, and one registered under a working directory that no longer exists"
        register(1, TempRepo.init(), socketPath())
        register(2, repo.resolveSibling("gone"), socketPath())

        expect:
        pusher.push(state, "hello") == new PushResult(Role.CLAUDE, PushOutcome.NO_SESSION, null)
    }

    void "a session running in a worktree of the repository is found, because it shares the state directory"() {
        given:
        Path worktree = TempRepo.addWorktree(repo, "feature")
        Path socket = socketPath()
        def received = inbox(socket)
        register(7, worktree, socket)

        expect:
        pusher.push(state, "hi").outcome() == PushOutcome.PUSHED
        received.get().contains('"content":"hi"')
    }

    void "a session whose socket is gone is skipped for the next newest, and reported when none accept"() {
        given: "the newest registration is stale; an older one is live"
        Path live = socketPath()
        def received = inbox(live)
        register(11, repo, socketPath(), 2000L, "stale")
        register(10, repo, live, 1000L, "alive")

        expect:
        with(pusher.push(state, "hi")) {
            outcome() == PushOutcome.PUSHED
            detail().contains("alive")
        }
        received.get().contains('"content":"hi"')

        when: "only stale registrations remain"
        Files.delete(registry.resolve("10.json"))
        PushResult failed = pusher.push(state, "hi")

        then:
        failed.outcome() == PushOutcome.FAILED
        failed.detail().contains("stale")
    }

    void "a registration that is not JSON, or lacks a socket, is ignored"() {
        given:
        Files.writeString(registry.resolve("garbage.json"), "not json")
        Files.writeString(registry.resolve("99.json"), '{"pid":99,"cwd":"' + repo + '"}')
        Files.writeString(registry.resolve("99.key"), '{"peerToken":"x"}')

        expect:
        pusher.push(state, "hi").outcome() == PushOutcome.NO_SESSION
    }

    void "nothing is posted when Claude Code would hold it: the listener delivers instead"() {
        given: "a registration whose socket nothing is bound to, so a connection attempt would show as a failure"
        register(31, repo, socketPath())
        Path silentHome = Files.createTempDirectory("claude-home-silent")
        Files.createDirectories(silentHome.resolve(".claude"))
        if (setting != null) Files.writeString(silentHome.resolve(".claude/settings.json"), '{"crossSessionInbound": "' + setting + '"}')
        HostPusher cautious = new ClaudeSocketPusher(registry.toString(), silentHome.toString(), home,
                context.getBean(com.moltenbits.sideband.install.Installer), context.getBean(Sessions), context.getBean(ObjectMapper), Duration.ofSeconds(1))

        when:
        PushResult result = cautious.push(state, "hi")

        then:
        result.outcome() == PushOutcome.LISTENER_DELIVERS
        result.detail().contains("inbound " + verdict + " per")
        result.detail().contains("accept in " + silentHome.resolve(".claude/settings.json"))
        !result.detail().contains("did not accept the connection")

        where:
        setting  | verdict
        null     | "missing"
        "hold"   | "held"
        "refuse" | "refused"
    }

    void "a repository can tighten the user's accept, and then nothing is posted either"() {
        given:
        register(32, repo, socketPath())
        Files.createDirectories(repo.resolve(".claude"))
        Files.writeString(repo.resolve(".claude/settings.local.json"), '{"crossSessionInbound": "refuse"}')

        expect:
        with(pusher.push(state, "hi")) {
            outcome() == PushOutcome.LISTENER_DELIVERS
            detail().contains("inbound refused per ")
            detail().contains("/.claude/settings.local.json); ")
        }
    }

    void "the running session's own recorded mode wins over the settings, in both directions"() {
        given:
        Path socket = socketPath()
        def received = inbox(socket)
        register(41, repo, socket, 1000L, "joined", "s1")
        Sessions sessions = context.getBean(Sessions)

        when: "the session joined listening, although the settings would allow a push"
        sessions.join(state, Role.CLAUDE, "s1", false, Delivery.LISTEN)
        PushResult listening = pusher.push(state, "hi")

        then:
        listening.outcome() == PushOutcome.LISTENER_DELIVERS
        listening.detail().contains("joined listening")
        !received.isDone()

        when: "the same session rejoined for pushes, although the settings a writer sees now would hold them"
        sessions.join(state, Role.CLAUDE, "s1", true, Delivery.PUSH)
        Path silentHome = Files.createTempDirectory("claude-home-silent")
        HostPusher trusting = new ClaudeSocketPusher(registry.toString(), silentHome.toString(), home,
                context.getBean(com.moltenbits.sideband.install.Installer), sessions, context.getBean(ObjectMapper), Duration.ofSeconds(5))
        PushResult pushed = trusting.push(state, "hi")

        then:
        pushed.outcome() == PushOutcome.PUSHED
        received.get(30, TimeUnit.SECONDS).contains('"content":"hi"')
    }

    void "a record left by an earlier session does not bind a new one: the files decide for a session that has not joined"() {
        given: "the session that joined is gone; a newer one is registered and has not joined"
        Path socket = socketPath()
        def received = inbox(socket)
        register(51, repo, socket, 2000L, "fresh", "new-session")
        Sessions sessions = context.getBean(Sessions)

        when: "the old record says listen, and the settings accept pushes"
        sessions.join(state, Role.CLAUDE, "old-session", false, Delivery.LISTEN)
        PushResult pushed = pusher.push(state, "hi")

        then:
        pushed.outcome() == PushOutcome.PUSHED
        received.get(30, TimeUnit.SECONDS).contains('"content":"hi"')

        when: "the old record says push, and the settings a writer sees would hold"
        sessions.join(state, Role.CLAUDE, "old-session", false, Delivery.PUSH)
        Path silentHome = Files.createTempDirectory("claude-home-silent")
        HostPusher cautious = new ClaudeSocketPusher(registry.toString(), silentHome.toString(), home,
                context.getBean(com.moltenbits.sideband.install.Installer), sessions, context.getBean(ObjectMapper), Duration.ofSeconds(5))
        PushResult held = cautious.push(state, "hi")

        then:
        held.outcome() == PushOutcome.LISTENER_DELIVERS
        held.detail().contains("has not joined")
    }

    void "the mode is judged per candidate: a stale registration ahead of the joined session changes nothing"() {
        given: "the newest registration is stale and unjoined; the older live one is the session that joined listening"
        Path live = socketPath()
        def received = inbox(live)
        register(61, repo, socketPath(), 2000L, "stale", "unjoined")
        register(60, repo, live, 1000L, "listening", "s-listen")
        context.getBean(Sessions).join(state, Role.CLAUDE, "s-listen", false, Delivery.LISTEN)

        expect: "the files accept pushes, but the joined session listens, so nothing is posted to it"
        pusher.push(state, "hi").outcome() == PushOutcome.LISTENER_DELIVERS
        !received.isDone()
    }

    void "a candidate the files would hold is skipped for an older session that joined for pushes"() {
        given: "the newest registration is unjoined, the older live one joined for pushes, and the files would hold"
        Path live = socketPath()
        def received = inbox(live)
        register(71, repo, socketPath(), 2000L, "unjoined", "u1")
        register(70, repo, live, 1000L, "pushing", "s-push")
        Sessions sessions = context.getBean(Sessions)
        sessions.join(state, Role.CLAUDE, "s-push", false, Delivery.PUSH)
        Path silentHome = Files.createTempDirectory("claude-home-silent")
        HostPusher cautious = new ClaudeSocketPusher(registry.toString(), silentHome.toString(), home,
                context.getBean(com.moltenbits.sideband.install.Installer), sessions, context.getBean(ObjectMapper), Duration.ofSeconds(5))

        expect:
        with(cautious.push(state, "hi")) {
            outcome() == PushOutcome.PUSHED
            detail().contains("pushing")
        }
        received.get(30, TimeUnit.SECONDS).contains('"content":"hi"')
    }

    void "an absent registry directory means no session"() {
        given:
        HostPusher lone = new ClaudeSocketPusher(registry.resolve("missing").toString(), fakeHome.toString(), home,
                context.getBean(com.moltenbits.sideband.install.Installer), context.getBean(Sessions), context.getBean(ObjectMapper), Duration.ofSeconds(1))

        expect:
        lone.push(state, "hi").outcome() == PushOutcome.NO_SESSION
    }
    void "a frame over Claude Code's inbox cap is refused before any connection, counting the escaped form: #label"() {
        given: "a registration whose socket nothing is bound to: a connection attempt would fail with a different reason"
        register(5, repo, socketPath())

        when:
        PushResult result = pusher.push(state, text)

        then:
        result.outcome() == PushOutcome.FAILED
        result.detail().contains("over Claude Code's inbox cap of 1000000")
        result.detail().contains("pending lists it")
        !result.detail().contains("did not accept the connection")

        where:
        label            | text
        "plain"          | "x" * 1_000_000
        "escaped quotes" | '"' * 600_000         // every quote serializes as two characters
        "newlines"       | "\n" * 600_000        // so does every newline
        "non-ascii"      | "\u00e9" * 1_000_000  // counted as characters, however they serialize
    }

    void "the cap is exact: the largest frame that fits is posted whole, one more character is refused"() {
        given:
        int overhead = context.getBean(ObjectMapper).writeValueAsString([type: "user", message: [role: "user", content: ""]]).length() + 1
        String largest = "w" * (ClaudeSocketPusher.FRAME_CAP - overhead)
        Path socket = socketPath()
        def received = inbox(socket)
        register(9, repo, socket)

        expect:
        pusher.push(state, largest + "w").outcome() == PushOutcome.FAILED
        pusher.push(state, largest).outcome() == PushOutcome.PUSHED
        context.getBean(ObjectMapper).readValue(received.get(30, TimeUnit.SECONDS), Map).message.content == largest
    }

    @Timeout(20)
    void "a session that accepts the connection but never reads is given up on after the timeout"() {
        given: "a bound socket with nobody draining it, and a frame far larger than its buffer"
        Path socket = socketPath()
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        servers << server
        register(8, repo, socket)
        HostPusher impatient = new ClaudeSocketPusher(registry.toString(), fakeHome.toString(), home,
                context.getBean(com.moltenbits.sideband.install.Installer), context.getBean(Sessions), context.getBean(ObjectMapper), Duration.ofSeconds(1))
        long started = System.nanoTime()

        when:
        PushResult result = impatient.push(state, "z" * 900_000)

        then:
        result.outcome() == PushOutcome.FAILED
        result.detail().contains("within 1s")
        Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10)
    }

    void "a registration whose socket path the platform rejects is skipped for a valid older one"() {
        given:
        Path live = socketPath()
        def received = inbox(live)
        register(21, repo, Path.of("/tmp/placeholder.sock"), 2000L, "malformed")
        Files.writeString(registry.resolve("21.json"),
                Files.readString(registry.resolve("21.json")).replace("/tmp/placeholder.sock", "/tmp/bad\\u0000name.sock"))
        register(20, repo, live, 1000L, "alive")

        expect:
        with(pusher.push(state, "hi")) {
            outcome() == PushOutcome.PUSHED
            detail().contains("alive")
        }
        received.get().contains('"content":"hi"')
    }
}
