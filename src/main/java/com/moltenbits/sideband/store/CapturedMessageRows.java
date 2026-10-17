package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;

/** The {@code captured_messages} table: at most one row per client, session, and message. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface CapturedMessageRows extends CrudRepository<CapturedMessageRow, Long> {

    List<CapturedMessageRow> findByRoleAndSessionIdAndMessageIdInList(String role, String sessionId, Collection<String> messageIds);
}
