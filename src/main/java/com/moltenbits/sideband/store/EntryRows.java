package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/** The {@code entries} table. Rows are only ever inserted; nothing updates or deletes one. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface EntryRows extends CrudRepository<EntryRow, Long> {

    List<EntryRow> findBySeqGreaterThanOrderBySeq(long seq);

    Optional<EntryRow> findByMessageId(String messageId);

    Optional<Long> findMaxSeq();
}
