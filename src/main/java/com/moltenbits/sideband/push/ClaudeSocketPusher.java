package com.moltenbits.sideband.push;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.protocol.ParticipantId;
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
import java.util.regex.Pattern;
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
 * <p>
 * Claude Code treats every frame on this socket as one of its own sessions speaking, and
 * introduces it to the model as such; nothing in the frame changes that introduction. What
 * the frame can carry is the shape Claude Code's own cross-session messaging gives its
 * payload: a {@code <cross-session-message>} tag whose {@code from-name} the receiving side
 * parses into the message's origin. The envelope travels inside that tag, named for the
 * entry's author, so the message is attributed to Codex or the operator rather than to an
 * anonymous session.
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
    public PushResult push(Path stateDirectory, ParticipantId from, String text) {
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
            frame = json.writeValueAsString(new Frame("user", new Message("user", attributed(from, text)))) + "\n";
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

    /**
     * The text in the shape Claude Code's own cross-session messaging sends: the tag, the
     * author's display name, a newline, the envelope, a newline, and the closing tag. Claude
     * Code parses only this exact form, and a name it would rewrite (quotes, angle brackets,
     * control characters, more than 64 characters) would make it fall back to an anonymous
     * message; the display names here contain none of those.
     */
    static String attributed(ParticipantId from, String text) {
        return "<cross-session-message from-name=\"" + from.displayName() + "\">\n" + canonical(text) + "\n</cross-session-message>";
    }

    /**
     * Where Claude Code would see the closing tag inside the envelope: an opening bracket or
     * one of the lookalikes it lists, then anything but a name character, then a slash or a
     * lookalike, then the tag name in any case with invisible characters allowed between its
     * letters, and then not a name character. Its own serializer escapes such a bracket, and
     * it recognizes only text that its serializer would leave alone.
     */
    private static final Pattern CLOSING_TAG_INSIDE = Pattern.compile(
            "[<\\u02C2\\u1438\\u2039\\u226E\\u227A\\u22D6\\u2329\\u276C\\u276E\\u2770\\u27E8\\u29FC\\u3008\\uFE64\\uFF1C]"
                    + "(?=[^A-Za-z0-9_\\-]*[/\\u2044\\u2215\\uFF0F][^A-Za-z0-9_\\-]*"
                    + spaced("cross-session-message") + "(?:[^A-Za-z0-9_\\-]|$))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * The tag name as a pattern: each letter, with invisible characters allowed between them and
     * any dash for its hyphens. The invisible set is Claude Code's own list (2.1.263): the format
     * characters, Hangul fillers, and combining marks it names, and the controls; no Unicode
     * category covers it, so the list is spelled out, with the format and nonspacing-mark
     * categories added as a margin.
     */
    private static String spaced(String name) {
        String invisible = "[\\u00AD\\u034F\\u0600-\\u0605\\u061C\\u06DD\\u070F\\u0890\\u0891\\u08E2\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0\\uFFF0-\\uFFFB\\x{110BD}\\x{110CD}\\x{13430}-\\x{1343F}\\x{1BCA0}-\\x{1BCA3}\\x{1D173}-\\x{1D17A}\\x{E0000}-\\x{E0FFF}\\u0300-\\u0344\\u0346-\\u036F\\u0483-\\u0489\\u0591-\\u05BD\\u05BF\\u05C1\\u05C2\\u05C4\\u05C5\\u05C7\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06DC\\u06DF-\\u06E4\\u06E7\\u06E8\\u06EA-\\u06ED\\u1AB0-\\u1AFF\\u1DC0-\\u1DFF\\u20D0-\\u20FF\\u3099\\u309A\\uFE20-\\uFE2F\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u2028\\u2029\\p{Cf}\\p{Mn}]*";
        String dash = "[\\-\\p{Pd}\\u2212\\u207B\\u208B\\u02D7\\u2796\\u2043\\u30FC\\uFF70]";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (i > 0) {
                out.append(invisible);
            }
            char c = name.charAt(i);
            out.append(c == '-' ? dash : String.valueOf(c));
        }
        return out.toString();
    }

    /**
     * The envelope with every bracket that would read as the closing tag spelled as the JSON
     * escape of that character. The envelope is the marker line and then JSON, in which the
     * six-character escape of a bracket is a legal spelling of it: a reader decoding the
     * entries gets every body back unchanged, and Claude Code's serializer, which escapes
     * only a literal bracket, would leave the text as it is, so it recognizes the frame.
     * Brackets anywhere else, generics and markup among them, stay as they are, so the
     * envelope reads as it was written. The Sideband journal never sees this spelling.
     */
    static String canonical(String envelope) {
        return CLOSING_TAG_INSIDE.matcher(envelope).replaceAll(match -> String.format("\\\\u%04x", (int) match.group().charAt(0)));
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
