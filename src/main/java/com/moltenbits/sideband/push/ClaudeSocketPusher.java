package com.moltenbits.sideband.push;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Wakes a Claude Code session by posting the text to its inbox socket, the same channel
 * Claude Code's own cross-session messaging uses. Claude Code registers every session in
 * {@code ~/.claude/sessions/<pid>.json} with its working directory and socket path, so the
 * recipient is whichever registered session works in this repository: no Sideband record
 * is needed, and a Claude that has never joined still receives the envelope. An idle
 * session starts a new turn with it; a busy one reads it between tool calls.
 * <p>
 * Registrations are tried newest first. A socket that refuses the connection belongs to a
 * session that has ended, so the next is tried; the entry waits in the journal when none
 * accepts. On macOS and Linux the connection needs no auth line.
 */
@Singleton
class ClaudeSocketPusher implements HostPusher {

    private final Path registry;
    private final SidebandHome home;
    private final ObjectMapper json;

    ClaudeSocketPusher(@Value("${sideband.claude.sessions-directory:}") String registry, SidebandHome home, ObjectMapper json) {
        this.registry = registry == null || registry.isBlank()
                ? Path.of(System.getProperty("user.home"), ".claude", "sessions")
                : Path.of(registry);
        this.home = home;
        this.json = json;
    }

    @Override
    public Role role() {
        return Role.CLAUDE;
    }

    @Override
    public PushResult push(Path stateDirectory, String text) {
        List<Registration> candidates = registered(stateDirectory);
        if (candidates.isEmpty()) {
            return new PushResult(Role.CLAUDE, PushOutcome.NO_SESSION, null);
        }
        String failure = null;
        for (Registration candidate : candidates) {
            try {
                post(Path.of(candidate.messagingSocketPath()), text);
                return new PushResult(Role.CLAUDE, PushOutcome.PUSHED,
                        "posted to " + candidate.messagingSocketPath() + " (session " + candidate.name() + ", pid " + candidate.pid() + ")");
            } catch (IOException e) {
                failure = "session " + candidate.name() + " (pid " + candidate.pid() + ") at " + candidate.messagingSocketPath()
                        + " did not accept the connection: " + e.getMessage();
            }
        }
        return new PushResult(Role.CLAUDE, PushOutcome.FAILED, failure);
    }

    /** Sessions registered for this repository, newest first. Anything unreadable or elsewhere is skipped. */
    private List<Registration> registered(Path stateDirectory) {
        if (!Files.isDirectory(registry)) {
            return List.of();
        }
        List<Registration> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(registry)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                Registration registration = read(file);
                if (registration != null && worksIn(registration, stateDirectory)) {
                    found.add(registration);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        found.sort(Comparator.comparingLong((Registration r) -> r.startedAt() == null ? 0L : r.startedAt()).reversed());
        return found;
    }

    private @Nullable Registration read(Path file) {
        try {
            Registration registration = json.readValue(Files.readString(file, UTF_8), Registration.class);
            return registration == null || registration.cwd() == null || registration.messagingSocketPath() == null
                    ? null : registration;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** True when the session's working directory resolves to the same Sideband state, worktrees included. */
    private boolean worksIn(Registration registration, Path stateDirectory) {
        try {
            Path cwd = Path.of(registration.cwd());
            return Files.isDirectory(cwd) && same(home.locate(cwd), stateDirectory);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean same(Path a, Path b) {
        return canonical(a).equals(canonical(b));
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** One newline-terminated JSON frame, then close: Claude Code reads a complete line and needs nothing more. */
    private void post(Path socket, String text) throws IOException {
        byte[] frame = (json.writeValueAsString(new Frame("user", new Message("user", text))) + "\n").getBytes(UTF_8);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.shutdownOutput();
        }
    }

    /** The fields of a Claude Code session registration this pusher reads; the rest are ignored. */
    @Serdeable
    record Registration(@Nullable Long pid, @Nullable String cwd, @Nullable String messagingSocketPath,
                        @Nullable String name, @Nullable Long startedAt) {
    }

    @Serdeable
    record Frame(String type, Message message) {
    }

    @Serdeable
    record Message(String role, String content) {
    }
}
