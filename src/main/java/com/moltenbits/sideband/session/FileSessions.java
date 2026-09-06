package com.moltenbits.sideband.session;

import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.locking.Lock;
import com.moltenbits.sideband.locking.Locks;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/** One JSON file per role under {@code sessions/}. */
@Singleton
class FileSessions implements Sessions {

    static final String DIRECTORY = "sessions";

    private final Journal journal;
    private final Locks locks;
    private final Clock clock;
    private final ObjectMapper json;

    FileSessions(Journal journal, Locks locks, Clock clock, ObjectMapper json) {
        this.journal = journal;
        this.locks = locks;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public Optional<Session> load(Path stateDirectory, Role role) {
        Path file = file(stateDirectory, role);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readString(file, UTF_8), Session.class));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    @Override
    public Session activate(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid, boolean replace) {
        try (Lock ignored = locks.acquire(stateDirectory)) {
            Optional<Session> existing = load(stateDirectory, role);
            if (existing.isPresent() && !existing.get().id().equals(sessionId) && !replace && existing.get().isLive()) {
                throw new SessionConflictException(existing.get());
            }
            long end = journal.readCompleteFrom(stateDirectory.resolve(Journal.FILE_NAME), 0).end();
            Session session = new Session(sessionId, now(), parentPid, end, end);
            save(stateDirectory, role, session);
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException("could not activate " + role.id() + " in " + stateDirectory, e);
        }
    }

    @Override
    public SessionRefresh refresh(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid) {
        try (Lock ignored = locks.acquire(stateDirectory)) {
            Optional<Session> loaded = load(stateDirectory, role);
            if (loaded.isEmpty()) {
                return SessionRefresh.NOT_ACTIVE;
            }
            Session existing = loaded.get();
            if (!existing.id().equals(sessionId)) {
                // The same host process presenting a new conversation id (Claude Code after
                // /clear) is this session continuing, not a second session.
                if (parentPid != null && parentPid.equals(existing.parentPid()) && isAlive(parentPid)) {
                    save(stateDirectory, role, existing.withId(sessionId));
                    return SessionRefresh.REFRESHED;
                }
                return SessionRefresh.SESSION_MISMATCH;
            }
            if (existing.isLive()) {
                return SessionRefresh.READY;
            }
            if (parentPid == null || !isAlive(parentPid)) {
                return SessionRefresh.CALLER_UNAVAILABLE;
            }
            save(stateDirectory, role, existing.withParentPid(parentPid));
            return SessionRefresh.REFRESHED;
        } catch (IOException e) {
            throw new UncheckedIOException("could not refresh the " + role.id() + " session in " + stateDirectory, e);
        }
    }

    @Override
    public Session advance(Path stateDirectory, Role role, long offset) {
        try (Lock ignored = locks.acquire(stateDirectory)) {
            Session session = load(stateDirectory, role).orElseThrow(
                    () -> new IllegalStateException(role.id() + " has no session in " + stateDirectory));
            Session advanced = session.withOffset(offset);
            if (advanced.offset() != session.offset()) {
                save(stateDirectory, role, advanced);
            }
            return advanced;
        } catch (IOException e) {
            throw new UncheckedIOException("could not advance the " + role.id() + " session in " + stateDirectory, e);
        }
    }

    private static boolean isAlive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Write to a temporary sibling, fsync, then rename so readers never see a torn file. */
    private void save(Path stateDirectory, Role role, Session session) throws IOException {
        Path file = file(stateDirectory, role);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = json.writeValueAsString(session).getBytes(UTF_8);
        try (FileChannel channel = FileChannel.open(temp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    static Path file(Path stateDirectory, Role role) {
        return stateDirectory.resolve(DIRECTORY).resolve(role.id() + ".json");
    }
}
