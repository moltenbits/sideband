package com.moltenbits.sideband.store;

import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** One row per role in the {@code sessions} table, replaced in the same transaction that reads the journal's end. */
@Singleton
class SqliteSessions implements Sessions {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Database database;
    private final SessionRows rows;
    private final SqliteJournal journal;
    private final Clock clock;

    SqliteSessions(Database database, SessionRows rows, SqliteJournal journal, Clock clock) {
        this.database = database;
        this.rows = rows;
        this.journal = journal;
        this.clock = clock;
    }

    @Override
    public Optional<Session> load(Path stateDirectory, Role role) {
        return database.read(stateDirectory, Optional.empty(), () -> load(role));
    }

    @Override
    public Session join(Path stateDirectory, Role role, String sessionId, boolean resume) {
        return database.write(stateDirectory, () -> {
            long end = journal.end();
            long offset = resume ? Math.min(load(role).map(Session::offset).orElse(0L), end) : end;
            Session session = new Session(sessionId, now(), end, offset, resume);
            save(rows, role, session);
            return session;
        });
    }

    @Override
    public Session advance(Path stateDirectory, Role role, long offset) {
        return database.write(stateDirectory, () -> {
            Session session = load(role).orElseThrow(
                    () -> new IllegalStateException(role.id() + " has no session in " + stateDirectory));
            Session advanced = session.withOffset(offset);
            if (advanced.offset() != session.offset()) {
                save(rows, role, advanced);
            }
            return advanced;
        });
    }

    @Override
    public Optional<Session> relocate(Path stateDirectory, Role role, String sessionId) {
        return database.write(stateDirectory, () -> {
            Optional<Session> existing = load(role);
            if (existing.isEmpty() || existing.get().id().equals(sessionId)) {
                return existing;
            }
            Session moved = existing.get().withId(sessionId);
            save(rows, role, moved);
            return Optional.of(moved);
        });
    }

    /** The role's record on the current connection. */
    Optional<Session> load(Role role) {
        return rows.findById(role.id()).map(SqliteSessions::session);
    }

    /** Writes the role's record on the current connection, replacing any earlier one. */
    static void save(SessionRows rows, Role role, Session session) {
        SessionRow row = new SessionRow(role.id(), session.id(), TIMESTAMP.format(session.startedAt()),
                session.watermark(), session.offset(), session.resumed());
        if (rows.existsById(role.id())) {
            rows.update(row);
        } else {
            rows.save(row);
        }
    }

    private static Session session(SessionRow row) {
        return new Session(row.sessionId(), OffsetDateTime.parse(row.startedAt(), TIMESTAMP),
                row.watermark(), row.bookmark(), row.resumed());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
