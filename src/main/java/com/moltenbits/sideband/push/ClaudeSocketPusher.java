package com.moltenbits.sideband.push;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
* accepts. On macOS and Linux the connection needs no auth line. A frame beyond Claude Code's
 * inbox cap is refused before any connection, and a session that accepts the connection but
 * stops reading is given up on after a bounded wait, so a writer is never left hanging.
 */
@Singleton
class ClaudeSocketPusher implements HostPusher {

    /*
     * Claude Code holds a frame from a process that is not the session's child unless the
     * operator's user settings accept cross-session messages, and a held frame is an approval
     * dialog for the operator on every entry. So nothing is posted unless the settings files
     * say the push will be delivered; otherwise the outcome is LISTENER_DELIVERS and Claude's
     * own listener, which the adapter starts in that case, delivers the entry.
     */

    /** Claude Code refuses a message whose serialized form passes about a million characters. */
    static final int FRAME_CAP = 1_000_000;

    private final Path registry;
    private final Path homeDirectory;
    private final SidebandHome home;
    private final Installer installer;
    private final ObjectMapper json;
    private final Duration timeout;

    @Inject
    ClaudeSocketPusher(@Value("${sideband.claude.sessions-directory:}") String registry,
                       @Value("${sideband.home-directory:}") String homeDirectory,
                       SidebandHome home, Installer installer, ObjectMapper json,
                       @Value("${sideband.claude.push-timeout-seconds:10}") long timeoutSeconds) {
        this(registry, homeDirectory, home, installer, json, Duration.ofSeconds(timeoutSeconds));
    }

    ClaudeSocketPusher(String registry, String homeDirectory, SidebandHome home, Installer installer, ObjectMapper json, Duration timeout) {
        this.homeDirectory = homeDirectory == null || homeDirectory.isBlank()
                ? Path.of(System.getProperty("user.home")) : Path.of(homeDirectory);
        this.registry = registry == null || registry.isBlank()
                ? this.homeDirectory.resolve(".claude").resolve("sessions")
                : Path.of(registry);
        this.home = home;
        this.installer = installer;
        this.json = json;
        this.timeout = timeout;
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
        // Claude Code holds a frame from a process that is not the session's child unless the
        // operator's settings accept cross-session messages, and a held frame is an approval
        // dialog on every entry. The settings are read here, on each append, so nothing is
        // posted while they say hold; Claude's own listener delivers then.
        InstallReport.Item inbound = installer.inbound(homeDirectory, home.projectRoot(stateDirectory));
        if (!inbound.state().equals("installed")) {
            return new PushResult(Role.CLAUDE, PushOutcome.LISTENER_DELIVERS,
                    "Claude Code would hold the push (inbound " + inbound.state() + " per " + inbound.path() + "); " + inbound.note());
        }
        String frame;
        try {
            frame = json.writeValueAsString(new Frame("user", new Message("user", text))) + "\n";
        } catch (IOException e) {
            return new PushResult(Role.CLAUDE, PushOutcome.FAILED, "could not serialize the envelope: " + e.getMessage());
        }
        if (frame.length() > FRAME_CAP) {
            return new PushResult(Role.CLAUDE, PushOutcome.FAILED, "the serialized frame is " + frame.length()
                    + " characters, over Claude Code's inbox cap of " + FRAME_CAP + "; the entry stays in the journal and pending lists it");
        }
        byte[] bytes = frame.getBytes(UTF_8);
        String failure = null;
        for (Registration candidate : candidates) {
            try {
                post(Path.of(candidate.messagingSocketPath()), bytes);
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
            if (registration == null || registration.cwd() == null || registration.messagingSocketPath() == null) {
                return null;
            }
            Path.of(registration.messagingSocketPath()); // a path the platform rejects is a registration to skip, not a failure
            return registration;
        } catch (IOException | RuntimeException e) { // InvalidPathException included
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

    /**
     * One newline-terminated JSON frame, then close: Claude Code reads a complete line and
     * needs nothing more. The connect and write run on their own thread; if they have not
     * finished within the timeout, the channel is closed under them and the attempt fails.
     */
    private void post(Path socket, byte[] frame) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            AtomicReference<IOException> failure = new AtomicReference<>();
            Thread writer = Thread.ofPlatform().daemon(true).name("sideband-claude-push").start(() -> {
                try {
                    channel.connect(UnixDomainSocketAddress.of(socket));
                    ByteBuffer buffer = ByteBuffer.wrap(frame);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.shutdownOutput();
                } catch (IOException e) {
                    failure.set(e);
                }
            });
            try {
                writer.join(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while posting");
            }
            if (writer.isAlive()) {
                channel.close(); // unblocks the writer with an AsynchronousCloseException
                throw new IOException("did not accept the frame within " + timeout.toSeconds() + "s");
            }
            if (failure.get() != null) {
                throw failure.get();
            }
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
