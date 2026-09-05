package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.locking.Lock;
import com.moltenbits.sideband.locking.Locks;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/** Cursors as JSON files under {@code cursors/}, replaced atomically under the shared lock. */
@Singleton
class FileRecipientState implements RecipientState {

    static final String DIRECTORY = "cursors";

    private final Journal journal;
    private final Locks locks;
    private final Clock clock;
    private final ObjectMapper json;

    FileRecipientState(Journal journal, Locks locks, Clock clock, ObjectMapper json) {
        this.journal = journal;
        this.locks = locks;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public Cursor load(Path stateDirectory, Role role) {
        Path file = cursorFile(stateDirectory, role);
        if (!Files.exists(file)) {
            return Cursor.empty(role);
        }
        try {
            Cursor cursor = json.readValue(Files.readString(file, UTF_8), Cursor.class);
            if (cursor.schema() != Cursor.SCHEMA) {
                throw new IllegalStateException(file + " has cursor schema " + cursor.schema() + "; this build reads " + Cursor.SCHEMA);
            }
            return cursor;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    @Override
    public Activation activate(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid, boolean replace) {
        try (Lock ignored = locks.acquire(stateDirectory)) {
            Read all = journal.readCompleteFrom(journalFile(stateDirectory), 0);
            Cursor cursor = load(stateDirectory, role);
            Session existing = cursor.session();
            if (existing != null && !existing.id().equals(sessionId) && !replace && existing.isLive()) {
                throw new SessionConflictException(existing);
            }
            Entry last = all.entries().isEmpty() ? null : all.entries().get(all.entries().size() - 1);
            Session session = new Session(sessionId, now(), parentPid,
                    last == null ? null : last.metadata().id(), all.end());
            cursor = reconcileOutgoing(cursor.withSession(session), role, all.entries());
            List<Entry> backlog = new ArrayList<>();
            OffsetDateTime at = now();
            for (Entry entry : all.entries()) {
                if (entry.end() <= session.watermarkEnd() && isOpen(cursor, entry)) {
                    backlog.add(entry);
                    cursor = cursor.withEntry(entry.metadata().id(), cursor.stateOf(entry.metadata().id()).seen(at));
                }
            }
            save(stateDirectory, cursor);
            return new Activation(session, backlog, all.diagnostics());
        } catch (IOException e) {
            throw new UncheckedIOException("could not activate " + role.id() + " in " + stateDirectory, e);
        }
    }

    /** Requests this role sent are pending unless recorded otherwise; replies to them are correlated. */
    private Cursor reconcileOutgoing(Cursor cursor, Role role, List<Entry> entries) {
        Map<String, EntryMetadata> byId = new HashMap<>();
        for (Entry entry : entries) {
            byId.put(entry.metadata().id(), entry.metadata());
        }
        for (Entry entry : entries) {
            EntryMetadata metadata = entry.metadata();
            if (Addressing.isOutgoingRequest(metadata, role) && !cursor.outgoing().containsKey(metadata.id())) {
                cursor = cursor.withOutgoing(metadata.id(), OutgoingState.PENDING);
            }
        }
        for (Entry entry : entries) {
            cursor = correlate(cursor, entry.metadata(), byId);
        }
        return cursor;
    }

    /** A reply answers the nearest pending outgoing request reachable through reply_to links. */
    private static Cursor correlate(Cursor cursor, EntryMetadata reply, Map<String, EntryMetadata> byId) {
        if (reply.replyTo() == null || reply.from().equals(ParticipantId.of(cursor.role()))) {
            return cursor;
        }
        Set<String> visited = new HashSet<>();
        String current = reply.replyTo();
        while (current != null && visited.add(current)) {
            OutgoingState outgoing = cursor.outgoing().get(current);
            if (outgoing != null) {
                return cursor.withOutgoing(current, outgoing.withReply(reply.id()));
            }
            EntryMetadata parent = byId.get(current);
            current = parent == null ? null : parent.replyTo();
        }
        return cursor;
    }

    @Override
    public Cursor markDelivered(Path stateDirectory, Role role, List<String> ids) {
        return update(stateDirectory, role, cursor -> {
            OffsetDateTime at = now();
            Read all = journal.readCompleteFrom(journalFile(stateDirectory), 0);
            Map<String, EntryMetadata> byId = new HashMap<>();
            for (Entry entry : all.entries()) {
                byId.put(entry.metadata().id(), entry.metadata());
            }
            for (String id : ids) {
                cursor = cursor.withEntry(id, cursor.stateOf(id).delivered(at));
                EntryMetadata metadata = byId.get(id);
                if (metadata != null) {
                    cursor = correlate(cursor, metadata, byId);
                }
            }
            return cursor;
        });
    }

    @Override
    public Cursor markSeen(Path stateDirectory, Role role, List<String> ids) {
        return update(stateDirectory, role, cursor -> {
            OffsetDateTime at = now();
            for (String id : ids) {
                cursor = cursor.withEntry(id, cursor.stateOf(id).seen(at));
            }
            return cursor;
        });
    }

    @Override
    public Cursor resolve(Path stateDirectory, Role role, List<String> ids, Resolution resolution) {
        return update(stateDirectory, role, cursor -> {
            OffsetDateTime at = now();
            for (String id : ids) {
                cursor = cursor.withEntry(id, cursor.stateOf(id).resolved(at, resolution));
            }
            return cursor;
        });
    }

    @Override
    public Cursor registerOutgoing(Path stateDirectory, Role role, String requestId) {
        return update(stateDirectory, role, cursor ->
                cursor.outgoing().containsKey(requestId) ? cursor : cursor.withOutgoing(requestId, OutgoingState.PENDING));
    }

    @Override
    public Cursor resolveOutgoing(Path stateDirectory, Role role, List<String> ids, OutgoingStatus status) {
        return update(stateDirectory, role, cursor -> {
            OffsetDateTime at = now();
            for (String id : ids) {
                OutgoingState state = cursor.outgoing().get(id);
                if (state == null) {
                    throw new IllegalArgumentException(id + " is not an outgoing request of " + role.id());
                }
                cursor = cursor.withOutgoing(id, state.resolved(status, at));
            }
            return cursor;
        });
    }

    @Override
    public Pending pending(Path stateDirectory, Role role) {
        Cursor cursor = load(stateDirectory, role);
        Read all = journal.readCompleteFrom(journalFile(stateDirectory), 0);
        long watermark = cursor.session() == null ? Long.MAX_VALUE : cursor.session().watermarkEnd();
        List<Entry> backlog = new ArrayList<>();
        List<Entry> live = new ArrayList<>();
        for (Entry entry : all.entries()) {
            if (isOpen(cursor, entry)) {
                (entry.end() <= watermark ? backlog : live).add(entry);
            }
        }
        Map<String, OutgoingState> outgoing = new HashMap<>();
        cursor.outgoing().forEach((id, state) -> {
            if (state.state() == OutgoingStatus.PENDING) {
                outgoing.put(id, state);
            }
        });
        return new Pending(backlog, live, outgoing);
    }

    @Override
    public boolean isOpen(Cursor cursor, Entry entry) {
        return Addressing.concerns(entry.metadata(), cursor.role()) && !cursor.isResolved(entry.metadata().id());
    }

    private Cursor update(Path stateDirectory, Role role, UnaryOperator<Cursor> change) {
        try (Lock ignored = locks.acquire(stateDirectory)) {
            Cursor updated = change.apply(load(stateDirectory, role));
            save(stateDirectory, updated);
            return updated;
        } catch (IOException e) {
            throw new UncheckedIOException("could not update the " + role.id() + " cursor in " + stateDirectory, e);
        }
    }

    /** Write to a temporary sibling, fsync, then rename over the cursor so readers never see a torn file. */
    private void save(Path stateDirectory, Cursor cursor) throws IOException {
        Path file = cursorFile(stateDirectory, cursor.role());
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = json.writeValueAsString(cursor).getBytes(UTF_8);
        try (FileChannel channel = FileChannel.open(temp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    static Path cursorFile(Path stateDirectory, Role role) {
        return stateDirectory.resolve(DIRECTORY).resolve(role.id() + ".json");
    }

    private static Path journalFile(Path stateDirectory) {
        return stateDirectory.resolve(Journal.FILE_NAME);
    }
}
