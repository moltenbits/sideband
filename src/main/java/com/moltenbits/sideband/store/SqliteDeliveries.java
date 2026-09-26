package com.moltenbits.sideband.store;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.session.Deliveries;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** One row per entry and instance in the {@code deliveries} table, replaced if the same entry is ever pushed again. */
@Singleton
class SqliteDeliveries implements Deliveries {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Database database;
    private final DeliveryRows rows;
    private final Clock clock;

    SqliteDeliveries(Database database, DeliveryRows rows, Clock clock) {
        this.database = database;
        this.rows = rows;
        this.clock = clock;
    }

    @Override
    public void record(Path stateDirectory, long seq, ParticipantId instance, String sessionId) {
        database.write(stateDirectory, () -> {
            String at = TIMESTAMP.format(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
            rows.findBySeqAndParticipant(seq, instance.value()).ifPresentOrElse(
                    existing -> rows.update(new DeliveryRow(existing.id(), seq, instance.value(), sessionId, at)),
                    () -> rows.save(new DeliveryRow(null, seq, instance.value(), sessionId, at)));
            return null;
        });
    }

    @Override
    public Map<Long, OffsetDateTime> pushedInto(Path stateDirectory, ParticipantId instance, String sessionId) {
        return database.read(stateDirectory, Map.of(), () -> {
            Map<Long, OffsetDateTime> pushed = new LinkedHashMap<>();
            for (DeliveryRow row : rows.findByParticipantAndSessionId(instance.value(), sessionId)) {
                pushed.put(row.seq(), OffsetDateTime.parse(row.pushedAt(), TIMESTAMP));
            }
            return pushed;
        });
    }
}
