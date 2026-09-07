package com.moltenbits.sideband.store;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.MessageIds;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.protocol.Delivery;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.protocol.Route;
import com.moltenbits.sideband.protocol.Wire;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.moltenbits.sideband.store.Schema.BACKLOG;
import static com.moltenbits.sideband.store.Schema.BODY;
import static com.moltenbits.sideband.store.Schema.CAUSED_BY;
import static com.moltenbits.sideband.store.Schema.CREATED_AT;
import static com.moltenbits.sideband.store.Schema.ENTRIES;
import static com.moltenbits.sideband.store.Schema.EXPECTS_REPLY;
import static com.moltenbits.sideband.store.Schema.ID;
import static com.moltenbits.sideband.store.Schema.LIVE;
import static com.moltenbits.sideband.store.Schema.RECIPIENTS;
import static com.moltenbits.sideband.store.Schema.REPLY_TO;
import static com.moltenbits.sideband.store.Schema.ROUTE;
import static com.moltenbits.sideband.store.Schema.SENDER;
import static com.moltenbits.sideband.store.Schema.SEQ;
import static com.moltenbits.sideband.store.Schema.TYPE;
import static com.moltenbits.sideband.store.Schema.VIA;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.max;

/** The journal as the {@code entries} table: one row per entry, the rowid as its position. */
@Singleton
class SqliteJournal implements Journal {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** Selected by name and type, so SQLite's loosely typed values come back as the declared Java types. */
    private static final List<Field<?>> COLUMNS = List.of(SEQ, ID, CREATED_AT, SENDER, VIA, RECIPIENTS, TYPE, ROUTE,
            REPLY_TO, CAUSED_BY, EXPECTS_REPLY, LIVE, BACKLOG, BODY);

    private final Database database;
    private final Clock clock;
    private final MessageIds ids;

    SqliteJournal(Database database, Clock clock, MessageIds ids) {
        this.database = database;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public Entry append(Path stateDirectory, Draft draft) {
        EntryMetadata metadata = new EntryMetadata(
                ids.next(),
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS),
                draft.from(), draft.via(), draft.to(), draft.type(), draft.route(),
                draft.replyTo(), draft.causedBy(), draft.expectsReply(), draft.delivery());
        return database.write(stateDirectory, ctx -> insert(ctx, metadata, draft.body()));
    }

    /** Inserts one complete entry and returns it with the position the store assigned. */
    static Entry insert(DSLContext ctx, EntryMetadata m, String body) {
        long seq = ctx.insertInto(ENTRIES)
                .set(ID, m.id())
                .set(CREATED_AT, TIMESTAMP.format(m.createdAt()))
                .set(SENDER, m.from().value())
                .set(VIA, m.via() == null ? null : m.via().id())
                .set(RECIPIENTS, m.to().stream().map(ParticipantId::value).collect(Collectors.joining(",")))
                .set(TYPE, m.type().id())
                .set(ROUTE, m.route().id())
                .set(REPLY_TO, m.replyTo())
                .set(CAUSED_BY, m.causedBy())
                .set(EXPECTS_REPLY, m.expectsReply())
                .set(LIVE, m.delivery().live().id())
                .set(BACKLOG, m.delivery().backlog().id())
                .set(BODY, body)
                .returningResult(SEQ)
                .fetchSingle()
                .value1();
        return new Entry(m, body, seq);
    }

    @Override
    public Read readAfter(Path stateDirectory, long position) {
        return database.read(stateDirectory, Read.empty(position), ctx -> {
            List<Entry> entries = ctx.select(COLUMNS).from(ENTRIES).where(SEQ.gt(position)).orderBy(SEQ).fetch(SqliteJournal::entry);
            long end = entries.isEmpty() ? position : entries.getLast().seq();
            return new Read(position, end, entries);
        });
    }

    @Override
    public Optional<Entry> find(Path stateDirectory, String id) {
        return database.read(stateDirectory, Optional.empty(),
                ctx -> ctx.select(COLUMNS).from(ENTRIES).where(ID.eq(id)).fetchOptional(SqliteJournal::entry));
    }

    @Override
    public long end(Path stateDirectory) {
        return database.read(stateDirectory, 0L, SqliteJournal::end);
    }

    static long end(DSLContext ctx) {
        return ctx.select(coalesce(max(SEQ), 0L)).from(ENTRIES).fetchSingle().value1();
    }

    static Entry entry(Record row) {
        String via = row.get(VIA);
        EntryMetadata metadata = new EntryMetadata(
                row.get(ID),
                OffsetDateTime.parse(row.get(CREATED_AT), TIMESTAMP),
                new ParticipantId(row.get(SENDER)),
                via == null ? null : Wire.fromId(Role.class, via),
                Arrays.stream(row.get(RECIPIENTS).split(",")).map(ParticipantId::new).toList(),
                Wire.fromId(MessageType.class, row.get(TYPE)),
                Wire.fromId(Route.class, row.get(ROUTE)),
                row.get(REPLY_TO),
                row.get(CAUSED_BY),
                row.get(EXPECTS_REPLY),
                new Delivery(Wire.fromId(DeliveryPolicy.class, row.get(LIVE)), Wire.fromId(DeliveryPolicy.class, row.get(BACKLOG))));
        return new Entry(metadata, row.get(BODY), row.get(SEQ));
    }
}
