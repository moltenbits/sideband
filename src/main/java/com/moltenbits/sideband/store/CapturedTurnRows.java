package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/** The {@code captured_turns} table: at most one row per project, client, session, and turn. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface CapturedTurnRows extends CrudRepository<CapturedTurnRow, Long> {

    List<CapturedTurnRow> findByProjectAndRoleAndSessionId(String project, String role, String sessionId);

    Optional<CapturedTurnRow> findByProjectAndRoleAndSessionIdAndTurnId(String project, String role, String sessionId, String turnId);
}
