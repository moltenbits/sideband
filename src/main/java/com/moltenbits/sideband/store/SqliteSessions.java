package com.moltenbits.sideband.store;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.InstanceRule;
import com.moltenbits.sideband.session.Joining;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One row per instance in the {@code sessions} table. Joining and following read the role's
 * records, decide by {@link InstanceRule}, and write what changed in the same transaction,
 * which SQLite serializes against every other writer.
 */
@Singleton
class SqliteSessions implements Sessions {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Database database;
    private final SessionRows rows;
    private final SqliteJournal journal;
    private final InstanceRule rule;
    private final Clock clock;

    SqliteSessions(Database database, SessionRows rows, SqliteJournal journal, InstanceRule rule, Clock clock) {
        this.database = database;
        this.rows = rows;
        this.journal = journal;
        this.rule = rule;
        this.clock = clock;
    }

    @Override
    public Optional<Session> load(Path stateDirectory, ParticipantId instance) {
        return database.read(stateDirectory, Optional.empty(), () -> rows.findById(instance.value()).map(SqliteSessions::session));
    }

    @Override
    public SortedMap<ParticipantId, Session> all(Path stateDirectory) {
        return database.read(stateDirectory, new TreeMap<>(), this::all);
    }

    @Override
    public SortedMap<ParticipantId, Session> records(Path stateDirectory, Role role) {
        return rule.ofRole(all(stateDirectory), role);
    }

    @Override
    public InstanceRule.Identified identify(Path stateDirectory, Role role, @Nullable String sessionId, @Nullable HostProcess caller) {
        return rule.identify(role, records(stateDirectory, role), sessionId, caller);
    }

    @Override
    public Joining join(Path stateDirectory, ParticipantId instance, String sessionId, @Nullable HostProcess process, boolean resume) {
        Role role = instance.role().orElseThrow(() -> new IllegalArgumentException(instance + " is not a client instance"));
        return database.write(stateDirectory, () -> {
            long end = journal.end();
            SortedMap<ParticipantId, Session> before = records(role);
            long offset = resume ? Math.min(Optional.ofNullable(before.get(instance)).map(Session::offset).orElse(0L), end) : end;
            InstanceRule.Joined joined = rule.join(before, instance,
                    new Session(sessionId, now(), end, offset, resume, process));
            save(before, joined.records());
            return new Joining(joined.records().get(instance), joined.replaced());
        });
    }

    @Override
    public Session advance(Path stateDirectory, ParticipantId instance, long offset) {
        return database.write(stateDirectory, () -> {
            Session session = rows.findById(instance.value()).map(SqliteSessions::session).orElseThrow(
                    () -> new IllegalStateException(instance + " has no session in " + stateDirectory));
            Session advanced = session.withOffset(offset);
            if (advanced.offset() != session.offset()) {
                rows.update(row(instance, advanced));
            }
            return advanced;
        });
    }

    @Override
    public InstanceRule.Followed follow(Path stateDirectory, Role role, String sessionId, @Nullable HostProcess caller) {
        if (!Files.exists(SidebandDataSource.file(stateDirectory))) {
            return new InstanceRule.Followed(null, false, List.of(), new TreeMap<>());
        }
        return database.write(stateDirectory, () -> {
            SortedMap<ParticipantId, Session> before = records(role);
            InstanceRule.Followed followed = rule.follow(before, sessionId, caller);
            save(before, followed.records());
            return followed;
        });
    }

    /** Every record on the current connection. */
    private SortedMap<ParticipantId, Session> all() {
        SortedMap<ParticipantId, Session> records = new TreeMap<>();
        for (SessionRow row : rows.findAll()) {
            records.put(new ParticipantId(row.participant()), session(row));
        }
        return records;
    }

    /** The role's records on the current connection. */
    private SortedMap<ParticipantId, Session> records(Role role) {
        return rule.ofRole(all(), role);
    }

    /** Writes what the rule changed on the current connection: removed, added, and altered records. */
    private void save(Map<ParticipantId, Session> before, Map<ParticipantId, Session> after) {
        for (ParticipantId gone : before.keySet()) {
            if (!after.containsKey(gone)) {
                rows.deleteById(gone.value());
            }
        }
        after.forEach((instance, session) -> {
            Session earlier = before.get(instance);
            if (earlier == null) {
                rows.save(row(instance, session));
            } else if (!earlier.equals(session)) {
                rows.update(row(instance, session));
            }
        });
    }

    private static SessionRow row(ParticipantId instance, Session session) {
        HostProcess process = session.process();
        return new SessionRow(instance.value(), session.id(), TIMESTAMP.format(session.startedAt()),
                session.watermark(), session.offset(), session.resumed(),
                process == null ? null : process.pid(),
                process == null ? null : process.startedAt().toString());
    }

    private static Session session(SessionRow row) {
        HostProcess process = row.hostPid() == null || row.hostStartedAt() == null ? null
                : new HostProcess(row.hostPid(), Instant.parse(row.hostStartedAt()));
        return new Session(row.sessionId(), OffsetDateTime.parse(row.startedAt(), TIMESTAMP),
                row.watermark(), row.bookmark(), row.resumed(), process);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
