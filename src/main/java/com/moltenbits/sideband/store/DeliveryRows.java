package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/** The {@code deliveries} table: at most one row per entry and instance. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface DeliveryRows extends CrudRepository<DeliveryRow, Long> {

    Optional<DeliveryRow> findBySeqAndParticipant(long seq, String participant);

    List<DeliveryRow> findByParticipantAndSessionId(String participant, String sessionId);
}
