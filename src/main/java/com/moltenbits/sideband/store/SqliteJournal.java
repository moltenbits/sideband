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

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** The journal as the {@code entries} table: one row per entry, the generated key as its position. */
@Singleton
class SqliteJournal implements Journal {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Database database;
    private final EntryRows rows;
    private final Clock clock;
    private final MessageIds ids;

    SqliteJournal(Database database, EntryRows rows, Clock clock, MessageIds ids) {
        this.database = database;
        this.rows = rows;
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
        return database.write(stateDirectory, () -> insert(rows, metadata, draft.body()));
    }

    /** Inserts one complete entry on the current connection and returns it with the position the store assigned. */
    static Entry insert(EntryRows rows, EntryMetadata m, String body) {
        EntryRow saved = rows.save(new EntryRow(null, m.id(), TIMESTAMP.format(m.createdAt()), m.from().value(),
                m.via() == null ? null : m.via().id(),
                m.to().stream().map(ParticipantId::value).collect(Collectors.joining(",")),
                m.type().id(), m.route().id(), m.replyTo(), m.causedBy(), m.expectsReply(),
                m.delivery().live().id(), m.delivery().backlog().id(), body));
        return new Entry(m, body, saved.seq());
    }

    @Override
    public Read readAfter(Path stateDirectory, long position) {
        return database.read(stateDirectory, Read.empty(position), () -> {
            List<Entry> entries = rows.findBySeqGreaterThanOrderBySeq(position).stream().map(SqliteJournal::entry).toList();
            long end = entries.isEmpty() ? position : entries.getLast().seq();
            return new Read(position, end, entries);
        });
    }

    @Override
    public Optional<Entry> find(Path stateDirectory, String id) {
        return database.read(stateDirectory, Optional.empty(), () -> rows.findByMessageId(id).map(SqliteJournal::entry));
    }

    @Override
    public long end(Path stateDirectory) {
        return database.read(stateDirectory, 0L, this::end);
    }

    /** The last position on the current connection, or zero when the table is empty. */
    long end() {
        return rows.findMaxSeq().orElse(0L);
    }

    static Entry entry(EntryRow row) {
        EntryMetadata metadata = new EntryMetadata(
                row.messageId(),
                OffsetDateTime.parse(row.createdAt(), TIMESTAMP),
                new ParticipantId(row.sender()),
                row.via() == null ? null : Wire.fromId(Role.class, row.via()),
                Arrays.stream(row.recipients().split(",")).map(ParticipantId::new).toList(),
                Wire.fromId(MessageType.class, row.type()),
                Wire.fromId(Route.class, row.route()),
                row.replyTo(),
                row.causedBy(),
                row.expectsReply(),
                new Delivery(Wire.fromId(DeliveryPolicy.class, row.live()), Wire.fromId(DeliveryPolicy.class, row.backlog())));
        return new Entry(metadata, row.body(), row.seq());
    }
}
