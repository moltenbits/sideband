package com.moltenbits.sideband.store;

import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static com.moltenbits.sideband.store.Schema.BOOKMARK;
import static com.moltenbits.sideband.store.Schema.RESUMED;
import static com.moltenbits.sideband.store.Schema.ROLE;
import static com.moltenbits.sideband.store.Schema.SESSIONS;
import static com.moltenbits.sideband.store.Schema.SESSION_ID;
import static com.moltenbits.sideband.store.Schema.STARTED_AT;
import static com.moltenbits.sideband.store.Schema.WATERMARK;

/** One row per role in the {@code sessions} table, replaced in the same transaction that reads the journal's end. */
@Singleton
class SqliteSessions implements Sessions {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Database database;
    private final Clock clock;

    SqliteSessions(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @Override
    public Optional<Session> load(Path stateDirectory, Role role) {
        return database.read(stateDirectory, Optional.empty(), ctx -> load(ctx, role));
    }

    @Override
    public Session join(Path stateDirectory, Role role, String sessionId, boolean resume) {
        return database.write(stateDirectory, ctx -> {
            long end = SqliteJournal.end(ctx);
            long offset = resume ? Math.min(load(ctx, role).map(Session::offset).orElse(0L), end) : end;
            Session session = new Session(sessionId, now(), end, offset, resume);
            save(ctx, role, session);
            return session;
        });
    }

    @Override
    public Session advance(Path stateDirectory, Role role, long offset) {
        return database.write(stateDirectory, ctx -> {
            Session session = load(ctx, role).orElseThrow(
                    () -> new IllegalStateException(role.id() + " has no session in " + stateDirectory));
            Session advanced = session.withOffset(offset);
            if (advanced.offset() != session.offset()) {
                save(ctx, role, advanced);
            }
            return advanced;
        });
    }

    @Override
    public Optional<Session> relocate(Path stateDirectory, Role role, String sessionId) {
        return database.write(stateDirectory, ctx -> {
            Optional<Session> existing = load(ctx, role);
            if (existing.isEmpty() || existing.get().id().equals(sessionId)) {
                return existing;
            }
            Session moved = existing.get().withId(sessionId);
            save(ctx, role, moved);
            return Optional.of(moved);
        });
    }

    static Optional<Session> load(DSLContext ctx, Role role) {
        return ctx.select(SESSION_ID, STARTED_AT, WATERMARK, BOOKMARK, RESUMED).from(SESSIONS)
                .where(ROLE.eq(role.id())).fetchOptional(SqliteSessions::session);
    }

    static void save(DSLContext ctx, Role role, Session session) {
        ctx.insertInto(SESSIONS)
                .set(ROLE, role.id())
                .set(SESSION_ID, session.id())
                .set(STARTED_AT, TIMESTAMP.format(session.startedAt()))
                .set(WATERMARK, session.watermark())
                .set(BOOKMARK, session.offset())
                .set(RESUMED, session.resumed())
                .onConflict(ROLE).doUpdate()
                .set(SESSION_ID, session.id())
                .set(STARTED_AT, TIMESTAMP.format(session.startedAt()))
                .set(WATERMARK, session.watermark())
                .set(BOOKMARK, session.offset())
                .set(RESUMED, session.resumed())
                .execute();
    }

    private static Session session(Record row) {
        return new Session(row.get(SESSION_ID), OffsetDateTime.parse(row.get(STARTED_AT), TIMESTAMP),
                row.get(WATERMARK), row.get(BOOKMARK), row.get(RESUMED));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
