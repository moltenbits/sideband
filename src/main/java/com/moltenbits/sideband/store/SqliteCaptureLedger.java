package com.moltenbits.sideband.store;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.CaptureLedger;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A session's capture state in the {@code capture_cursors} and {@code captured_turns}
 * tables. A commit checks the version and the instance, journals, and writes the new state
 * in one transaction, which SQLite serializes against every other writer.
 */
@Singleton
class SqliteCaptureLedger implements CaptureLedger {

    private final Database database;
    private final CaptureCursorRows cursors;
    private final CapturedTurnRows turns;
    private final SqliteJournal journal;
    private final SqliteSessions sessions;

    SqliteCaptureLedger(Database database, CaptureCursorRows cursors, CapturedTurnRows turns, SqliteJournal journal,
                        SqliteSessions sessions) {
        this.database = database;
        this.cursors = cursors;
        this.turns = turns;
        this.journal = journal;
        this.sessions = sessions;
    }

    @Override
    public State load(Path stateDirectory, Role client, String sessionId) {
        return database.read(stateDirectory, new State(0, null, Map.of()), () -> state(client, sessionId));
    }

    @Override
    public Optional<List<Entry>> commit(Path stateDirectory, Role client, String sessionId, @Nullable HostProcess caller,
                                        State read, @Nullable ParticipantId author, List<Draft> drafts,
                                        @Nullable Cursor cursor, Collection<Turn> changed) {
        return database.write(stateDirectory, () -> {
            Optional<CaptureCursorRow> row = cursors.findByRoleAndSessionId(client.id(), sessionId);
            long version = row.map(CaptureCursorRow::version).orElse(0L);
            if (version != read.version()) {
                return Optional.empty();
            }
            if (author != null && !author.equals(sessions.identifyHere(client, sessionId, caller).instance())) {
                return Optional.empty();
            }
            List<Entry> entries = drafts.stream().map(journal::insert).toList();
            CaptureCursorRow next = new CaptureCursorRow(row.map(CaptureCursorRow::id).orElse(null), client.id(), sessionId,
                    cursor == null ? null : cursor.transcript().toString(),
                    cursor == null ? null : cursor.position(),
                    cursor == null ? null : cursor.turn(),
                    version + 1);
            if (row.isPresent()) {
                cursors.update(next);
            } else {
                cursors.save(next);
            }
            for (Turn turn : changed) {
                Optional<CapturedTurnRow> existing = turns.findByRoleAndSessionIdAndTurnId(client.id(), sessionId, turn.id());
                CapturedTurnRow written = new CapturedTurnRow(existing.map(CapturedTurnRow::id).orElse(null), client.id(),
                        sessionId, turn.id(), turn.promptEntryId(), turn.mode().name(), turn.finalCaptured());
                if (existing.isPresent()) {
                    turns.update(written);
                } else {
                    turns.save(written);
                }
            }
            return Optional.of(entries);
        });
    }

    /** The session's state on the current connection. */
    private State state(Role client, String sessionId) {
        Optional<CaptureCursorRow> row = cursors.findByRoleAndSessionId(client.id(), sessionId);
        Cursor cursor = row.filter(r -> r.transcript() != null && r.position() != null)
                .map(r -> new Cursor(Path.of(r.transcript()), r.position(), r.turnId()))
                .orElse(null);
        Map<String, Turn> receipts = turns.findByRoleAndSessionId(client.id(), sessionId).stream()
                .map(r -> new Turn(r.turnId(), r.promptEntryId(), Mode.valueOf(r.mode()), r.finalCaptured()))
                .collect(Collectors.toMap(Turn::id, Function.identity()));
        return new State(row.map(CaptureCursorRow::version).orElse(0L), cursor, receipts);
    }
}
