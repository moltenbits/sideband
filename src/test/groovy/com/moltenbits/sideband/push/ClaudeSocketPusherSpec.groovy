package com.moltenbits.sideband.push

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.home.SidebandHome
import com.moltenbits.sideband.host.HostProcess
import com.moltenbits.sideband.host.HostProcesses
import com.moltenbits.sideband.install.Installer
import com.moltenbits.sideband.protocol.ParticipantId
import com.moltenbits.sideband.protocol.Role
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

    static final ParticipantId CLAUDE = ParticipantId.of(Role.CLAUDE)
    static final ParticipantId CODEX = ParticipantId.of(Role.CODEX)
    static final ParticipantId FABLE = ParticipantId.of(Role.CLAUDE, "fable")

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
    Sessions sessions = context.getBean(Sessions)
    /** This JVM, standing in for a running Claude Code process. */
    HostProcess self = context.getBean(HostProcesses).describe(ProcessHandle.current().pid()).get()
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

    String register(long pid, Path cwd, Path socket, long startedAt = 1000L, String name = "session-" + pid,
                    String sessionId = UUID.randomUUID().toString()) {
        Files.writeString(registry.resolve(pid + ".json"), context.getBean(ObjectMapper).writeValueAsString([
                pid: pid, sessionId: sessionId, cwd: cwd.toString(), startedAt: startedAt,
                version: "2.1.263", peerProtocol: 1, kind: "interactive", entrypoint: "cli",
                messagingSocketPath: socket.toString(), name: name, status: "idle"]))
        sessionId
    }

    void "the component is exposed only through its interface"() {
        expect:
        pusher instanceof ClaudeSocketPusher
    }

    void "the envelope is posted to the registered session's inbox as one user frame, in Claude Code's own cross-session shape"() {
        given:
        Path socket = socketPath()
        def received = inbox(socket)
        String sessionId = register(4242, repo, socket)
        String text = "[Sideband message]\n{\"intent\":\"Sideband delivery\",\"entries\":[]}"

        when:
        PushResult result = pusher.push(state, CLAUDE, CODEX, text)
        String wire = received.get()

        then:
        result.recipient() == CLAUDE
        result.outcome() == PushOutcome.PUSHED
        result.session() == sessionId
        result.detail().contains("session-4242")
        result.detail().contains("4242")
        wire.endsWith("\n")
        wire.count("\n") == 1
        Map frame = context.getBean(ObjectMapper).readValue(wire, Map)
        frame.keySet() == ["type", "message"] as Set
        frame.type == "user"
        frame.message.role == "user"
        frame.message.content == "<cross-session-message from-name=\"Codex\">\n" + text + "\n</cross-session-message>"
    }

    void "the frame names the entry's author, so Claude Code attributes the message to #from rather than to an anonymous session"() {
        given:
        Path socket = socketPath()
        def received = inbox(socket)
        register(4243, repo, socket)

        when:
        pusher.push(state, CLAUDE, new ParticipantId(from), "hi")
        Map frame = context.getBean(ObjectMapper).readValue(received.get(), Map)

        then:
        frame.message.content == "<cross-session-message from-name=\"" + name + "\">\nhi\n</cross-session-message>"

        where:
        from       | name
        "codex"    | "Codex"
        "operator" | "Operator"
        "claude"   | "Claude"
    }

    /**
     * What Claude Code 2.1.263 accepts as its own frame: the exact tag form, and a body its
     * serializer would leave alone. That serializer escapes any opening bracket, or lookalike,
     * that begins the closing tag, however the tag is cased or padded with invisible characters.
     */
    static final java.util.regex.Pattern HOST_FORM = ~/(?s)^<cross-session-message from-name="([^"<>\n\r]+)">\n(.*)\n<\/cross-session-message>$/
    static final java.util.regex.Pattern HOST_WOULD_ESCAPE = ~/(?iu)[<\u02C2\uFF1C\u2039](?!\\)[^A-Za-z0-9_\-]*[\/\uFF0F][^A-Za-z0-9_\-]*c[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*r[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*o[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*-[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*e[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*i[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*o[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*n[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*-[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*m[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*e[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*s[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*a[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*g[\u00AD\u034F\u0600-\u0605\u061C\u06DD\u070F\u0890\u0891\u08E2\u115F\u1160\u17B4\u17B5\u180B-\u180F\u200B-\u200F\u202A-\u202E\u2060-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF0-\uFFFB\x{110BD}\x{110CD}\x{13430}-\x{1343F}\x{1BCA0}-\x{1BCA3}\x{1D173}-\x{1D17A}\x{E0000}-\x{E0FFF}\u0300-\u0344\u0346-\u036F\u0483-\u0489\u0591-\u05BD\u05BF\u05C1\u05C2\u05C4\u05C5\u05C7\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20FF\u3099\u309A\uFE20-\uFE2F\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\u2028\u2029]*e(?:[^A-Za-z0-9_\-]|$)/

    void "a closing tag quoted in an entry body is spelled so Claude Code still recognizes the frame, and decodes unchanged: #label"() {
        given: "a real envelope: the marker line, then JSON whose entry body quotes the closing tag"
        ObjectMapper mapper = context.getBean(ObjectMapper)
        String envelope = "[Sideband message]\n" + mapper.writeValueAsString([intent: "Sideband delivery", entries: [[body: body]]])
        Path socket = socketPath()
        def received = inbox(socket)
        register(4244, repo, socket)

        when:
        pusher.push(state, CLAUDE, CODEX, envelope)
        String content = mapper.readValue(received.get(), Map).message.content
        def form = HOST_FORM.matcher(content)

        then: "the tag is the exact form, named for the author"
        form.matches()
        form.group(1) == "Codex"

        and: "the host's serializer would leave the inner text alone, so the host recognizes it"
        !HOST_WOULD_ESCAPE.matcher(form.group(2)).find()

        and: "the inner text is still the marker and JSON, and the entry body decodes to what was written"
        form.group(2).startsWith("[Sideband message]\n{")
        mapper.readValue(form.group(2).substring("[Sideband message]\n".length()), Map).entries[0].body == body

        where:
        label              | body
        "plain"            | "the frame ends with </cross-session-message> and Claude Code parses it"
        "upper case"       | "or </CROSS-SESSION-MESSAGE> in any case"
        "padded"           | "or < /cross-session-message> with filler after the bracket"
        "fullwidth"        | "or \uFF1C/cross-session-message> with a lookalike bracket"
        "invisible"        | "or </cross\u200B-session-message> with a zero-width space inside the name"
        "hangul filler"    | "or </cross\u115F-session-message> with a Hangul choseong filler, which no Unicode category calls invisible"
        "hangul filler 2"  | "or </cross-session\u3164-message> with a Hangul filler"
        "halfwidth filler" | "or </cross-session-mess\uFFA0age> with a halfwidth Hangul filler"
        "combining mark"   | "or </cro\u0301ss-session-message> with a combining acute accent"
        "astral tag char"  | "or </cross-session-messag\uDB40\uDC01e> with a tag character beyond the basic plane"
        "at the very end"  | "or </cross-session-message"
    }

    void "brackets that do not begin the closing tag are left as written, so the envelope reads as it was"() {
        given:
        ObjectMapper mapper = context.getBean(ObjectMapper)
        String body = "List<String> and <b>bold</b> and </cross-session-messages> and <cross-session-message> stay"
        String envelope = "[Sideband message]\n" + mapper.writeValueAsString([entries: [[body: body]]])
        Path socket = socketPath()
        def received = inbox(socket)
        register(4245, repo, socket)

        when:
        pusher.push(state, CLAUDE, CODEX, envelope)
        String content = mapper.readValue(received.get(), Map).message.content

        then:
        content == "<cross-session-message from-name=\"Codex\">\n" + envelope + "\n</cross-session-message>"
    }

    void "no registered session for this repository leaves the entry for backlog"() {
        given: "a session in another repository, and one registered under a working directory that no longer exists"
        register(1, TempRepo.init(), socketPath())
        register(2, repo.resolveSibling("gone"), socketPath())

        expect:
        pusher.push(state, CLAUDE, CODEX, "hello") == new PushResult(CLAUDE, PushOutcome.NO_SESSION, null)
    }

    void "a session running in a worktree of the repository is found, because it shares the state directory"() {
        given:
        Path worktree = TempRepo.addWorktree(repo, "feature")
        Path socket = socketPath()
        def received = inbox(socket)
        register(7, worktree, socket)

        expect:
        pusher.push(state, CLAUDE, CODEX, "hi").outcome() == PushOutcome.PUSHED
        received.get().contains('\\nhi\\n</cross-session-message>')
    }

    void "a session whose socket is gone is skipped for the next newest, and reported when none accept"() {
        given: "the newest registration is stale; an older one is live"
        Path live = socketPath()
        def received = inbox(live)
        register(11, repo, socketPath(), 2000L, "stale")
        register(10, repo, live, 1000L, "alive")

        expect:
        with(pusher.push(state, CLAUDE, CODEX, "hi")) {
            outcome() == PushOutcome.PUSHED
            detail().contains("alive")
        }
        received.get().contains('\\nhi\\n</cross-session-message>')

        when: "only stale registrations remain"
        Files.delete(registry.resolve("10.json"))
        PushResult failed = pusher.push(state, CLAUDE, CODEX, "hi")

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
        pusher.push(state, CLAUDE, CODEX, "hi").outcome() == PushOutcome.NO_SESSION
    }

    void "nothing is posted when Claude Code would hold it: the listener delivers instead"() {
        given: "a registration whose socket nothing is bound to, so a connection attempt would show as a failure"
        register(31, repo, socketPath())
        Path silentHome = Files.createTempDirectory("claude-home-silent")
        Files.createDirectories(silentHome.resolve(".claude"))
        if (setting != null) Files.writeString(silentHome.resolve(".claude/settings.json"), '{"crossSessionInbound": "' + setting + '"}')
        HostPusher cautious = new ClaudeSocketPusher(registry.toString(), silentHome.toString(), home,
                context.getBean(Installer), sessions, context.getBean(HostProcesses), context.getBean(ObjectMapper), Duration.ofSeconds(1))

        when:
        PushResult result = cautious.push(state, CLAUDE, CODEX, "hi")

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
        with(pusher.push(state, CLAUDE, CODEX, "hi")) {
            outcome() == PushOutcome.LISTENER_DELIVERS
            detail().contains("inbound refused per ")
            detail().contains("/.claude/settings.local.json); ")
        }
    }

    void "an absent registry directory means no session"() {
        given:
        HostPusher lone = new ClaudeSocketPusher(registry.resolve("missing").toString(), fakeHome.toString(), home,
                context.getBean(Installer), sessions, context.getBean(HostProcesses), context.getBean(ObjectMapper), Duration.ofSeconds(1))

        expect:
        lone.push(state, CLAUDE, CODEX, "hi").outcome() == PushOutcome.NO_SESSION
    }
    void "a frame over Claude Code's inbox cap is refused before any connection, counting the escaped form: #label"() {
        given: "a registration whose socket nothing is bound to: a connection attempt would fail with a different reason"
        register(5, repo, socketPath())

        when:
        PushResult result = pusher.push(state, CLAUDE, CODEX, text)

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
        String empty = "<cross-session-message from-name=\"Codex\">\n\n</cross-session-message>"
        int overhead = context.getBean(ObjectMapper).writeValueAsString([type: "user", message: [role: "user", content: empty]]).length() + 1
        String largest = "w" * (ClaudeSocketPusher.FRAME_CAP - overhead)
        Path socket = socketPath()
        def received = inbox(socket)
        register(9, repo, socket)

        expect:
        pusher.push(state, CLAUDE, CODEX, largest + "w").outcome() == PushOutcome.FAILED
        pusher.push(state, CLAUDE, CODEX, largest).outcome() == PushOutcome.PUSHED
        context.getBean(ObjectMapper).readValue(received.get(30, TimeUnit.SECONDS), Map).message.content
                == "<cross-session-message from-name=\"Codex\">\n" + largest + "\n</cross-session-message>"
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
                context.getBean(Installer), sessions, context.getBean(HostProcesses), context.getBean(ObjectMapper), Duration.ofSeconds(1))
        long started = System.nanoTime()

        when:
        PushResult result = impatient.push(state, CLAUDE, CODEX, "z" * 900_000)

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
        with(pusher.push(state, CLAUDE, CODEX, "hi")) {
            outcome() == PushOutcome.PUSHED
            detail().contains("alive")
        }
        received.get().contains('\\nhi\\n</cross-session-message>')
    }

    // --- which registration an instance's entry goes to (REQUIREMENTS.md 10.2) ---

    void "an entry for a named instance goes to the registration its record names, not the newest one"() {
        given:
        Path fableSocket = socketPath()
        def fableInbox = inbox(fableSocket)
        String fableSession = register(11, repo, fableSocket, 1000L)
        Path newerSocket = socketPath()
        def newer = inbox(newerSocket)
        register(12, repo, newerSocket, 2000L)
        sessions.join(state, FABLE, fableSession)

        when:
        PushResult result = pusher.push(state, FABLE, CODEX, "for fable")

        then:
        result.outcome() == PushOutcome.PUSHED
        result.session() == fableSession
        fableInbox.get(5, TimeUnit.SECONDS).contains("for fable")
        !newer.isDone()
    }

    void "a named instance is reached through its own record or not at all"() {
        given: "registrations that no fable record names"
        register(21, repo, socketPath(), 1000L)

        expect: "without a record"
        pusher.push(state, FABLE, CODEX, "hi").outcome() == PushOutcome.NO_SESSION

        when: "with a record whose session is no longer registered"
        sessions.join(state, FABLE, "gone-session")

        then:
        pusher.push(state, FABLE, CODEX, "hi").outcome() == PushOutcome.NO_SESSION
    }

    void "the unnamed instance goes to its own registration first, even when a newer one exists"() {
        given:
        Path ownSocket = socketPath()
        def own = inbox(ownSocket)
        String ownSession = register(31, repo, ownSocket, 1000L)
        Path newerSocket = socketPath()
        def newer = inbox(newerSocket)
        register(32, repo, newerSocket, 2000L)
        sessions.join(state, CLAUDE, ownSession)

        when:
        PushResult result = pusher.push(state, CLAUDE, CODEX, "for claude")

        then:
        result.session() == ownSession
        own.get(5, TimeUnit.SECONDS).contains("for claude")
        !newer.isDone()
    }

    void "the unnamed instance falls back to an unclaimed registration when its own refuses, as after a crash and an unjoined restart"() {
        given: "the joined session's registration lingers with a socket nothing listens on"
        String crashed = register(41, repo, socketPath(), 1000L)
        sessions.join(state, CLAUDE, crashed)
        Path restartedSocket = socketPath()
        def restarted = inbox(restartedSocket)
        String restartedSession = register(42, repo, restartedSocket, 2000L)

        when:
        PushResult result = pusher.push(state, CLAUDE, CODEX, "still reached")

        then:
        result.outcome() == PushOutcome.PUSHED
        result.session() == restartedSession
        restarted.get(5, TimeUnit.SECONDS).contains("still reached")
    }

    void "the unnamed instance never falls back onto a registration another instance holds"() {
        given:
        String fableSession = register(51, repo, socketPath(), 2000L)
        sessions.join(state, FABLE, fableSession)

        expect: "with only fable's session running, an entry for the unnamed Claude waits"
        pusher.push(state, CLAUDE, CODEX, "not for fable").outcome() == PushOutcome.NO_SESSION

        when: "an unclaimed older session is running too"
        Path freeSocket = socketPath()
        def free = inbox(freeSocket)
        String freeSession = register(52, repo, freeSocket, 1000L)

        then:
        pusher.push(state, CLAUDE, CODEX, "for the free one").session() == freeSession
        free.get(5, TimeUnit.SECONDS).contains("for the free one")
    }

    void "right after a clear the record's process finds the registration under its new session"() {
        given: "fable's record still names the session before the clear; the registration already names the new one"
        sessions.join(state, FABLE, "before-clear", self, false)
        Path socket = socketPath()
        def received = inbox(socket)
        String afterClear = register(self.pid(), repo, socket)

        when:
        PushResult result = pusher.push(state, FABLE, CODEX, "after the clear")

        then:
        result.outcome() == PushOutcome.PUSHED
        result.session() == afterClear
        received.get(5, TimeUnit.SECONDS).contains("after the clear")
    }

    void "a registration another record names by session is never taken by process"() {
        given: "fable's process now shows the unnamed instance's session, as after /resume inside one client"
        sessions.join(state, FABLE, "fable-before", self, false)
        String claudeSession = register(self.pid(), repo, socketPath())
        sessions.join(state, CLAUDE, claudeSession)

        expect:
        pusher.push(state, FABLE, CODEX, "not through claude's session").outcome() == PushOutcome.NO_SESSION
    }

    void "every registration the instance owns is tried before any other: another registration of the same session"() {
        given: "a resumed session registered twice, the newer registration's socket refusing"
        Path socket = socketPath()
        def received = inbox(socket)
        String resumed = register(101, repo, socket, 1000L)
        register(102, repo, socketPath(), 2000L, "session-102", resumed)
        sessions.join(state, recipient, resumed)

        when:
        PushResult result = pusher.push(state, recipient, CODEX, "still reachable")

        then:
        result.outcome() == PushOutcome.PUSHED
        result.session() == resumed
        received.get(5, TimeUnit.SECONDS).contains("still reachable")

        where:
        recipient << [FABLE, CLAUDE]
    }

    void "every registration the instance owns is tried before any other: its process under a new session after a stale one refuses"() {
        given: "the record's session is still registered by a dead socket, and its live process now shows a new session"
        String beforeClear = register(201, repo, socketPath(), 1000L)
        Path socket = socketPath()
        def received = inbox(socket)
        String afterClear = register(self.pid(), repo, socket, 2000L)
        sessions.join(state, recipient, beforeClear, self, false)

        when:
        PushResult result = pusher.push(state, recipient, CODEX, "after the clear")

        then:
        result.outcome() == PushOutcome.PUSHED
        result.session() == afterClear
        received.get(5, TimeUnit.SECONDS).contains("after the clear")

        where:
        recipient << [FABLE, CLAUDE]
    }
}
