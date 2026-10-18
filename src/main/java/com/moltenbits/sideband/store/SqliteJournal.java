package com.moltenbits.sideband.store;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.MessageIds;
import com.moltenbits.sideband.journal.Page;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.protocol.Delivery;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Route;
import com.moltenbits.sideband.protocol.Wire;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The journal as the {@code entries} table: one row per entry, the generated key as its position. */
@Singleton
class SqliteJournal implements Journal {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Database database;
    private final EntryRows rows;
    private final EntryProjectRows memberships;
    private final Clock clock;
    private final MessageIds ids;

    SqliteJournal(Database database, EntryRows rows, EntryProjectRows memberships, Clock clock, MessageIds ids) {
        this.database = database;
        this.rows = rows;
        this.memberships = memberships;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public Entry append(Path stateDirectory, Draft draft) {
        return database.write(stateDirectory, () -> insert(draft));
    }

    /** Gives the draft its identity and inserts it on the current connection, inside whatever transaction is open. */
    Entry insert(Draft draft) {
        EntryMetadata metadata = new EntryMetadata(
                ids.next(),
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS),
                draft.from(), draft.via(), draft.to(), draft.type(), draft.route(),
                draft.replyTo(), draft.causedBy(), draft.expectsReply(), draft.delivery());
        return insert(metadata, draft.body());
    }

    /**
     * Inserts one complete entry on the current connection, with a row for each project it
     * involves, and returns it with the position the store assigned.
     */
    Entry insert(EntryMetadata m, String body) {
        EntryRow saved = rows.save(new EntryRow(null, m.id(), TIMESTAMP.format(m.createdAt()), m.from().value(),
                m.via() == null ? null : m.via().value(),
                m.to().stream().map(ParticipantId::value).collect(Collectors.joining(",")),
                m.type().id(), m.route().id(), m.replyTo(), m.causedBy(), m.expectsReply(),
                m.delivery().live().id(), m.delivery().backlog().id(), body));
        projects(m).forEach(project -> memberships.save(new EntryProjectRow(null, project, saved.seq())));
        return new Entry(m, body, saved.seq());
    }

    /** Every project the entry's author, via, and recipients belong to, each once. */
    static Set<String> projects(EntryMetadata m) {
        return Stream.concat(Stream.of(m.from(), m.via()), m.to().stream())
                .filter(Objects::nonNull)
                .flatMap(participant -> participant.project().stream())
                .collect(Collectors.toCollection(TreeSet::new));
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
    public Read readAfter(Path stateDirectory, String project, long position) {
        return database.read(stateDirectory, Read.empty(position), () -> {
            List<Entry> entries = entries(memberships.findSeqByProjectAndSeqGreaterThanOrderBySeq(project, position));
            long end = entries.isEmpty() ? position : entries.getLast().seq();
            return new Read(position, end, entries);
        });
    }

    @Override
    public Page readBefore(Path stateDirectory, String project, long position, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page must hold at least one entry");
        }
        return database.read(stateDirectory, new Page(List.of(), false), () -> {
            List<Entry> entries = entries(memberships.findSeqByProjectAndSeqLessThanOrderBySeqDesc(project, position,
                    Pageable.from(0, limit))).reversed();
            return new Page(entries, !entries.isEmpty() && memberships.existsByProjectAndSeqLessThan(project, entries.getLast().seq()));
        });
    }

    /** The entries at {@code seqs}, oldest first, on the current connection. */
    private List<Entry> entries(List<Long> seqs) {
        return seqs.isEmpty() ? List.of() : rows.findBySeqInListOrderBySeq(seqs).stream().map(SqliteJournal::entry).toList();
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
                row.via() == null ? null : new ParticipantId(row.via()),
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
