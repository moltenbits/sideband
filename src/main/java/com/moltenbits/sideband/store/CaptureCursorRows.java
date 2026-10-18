package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

/** The {@code capture_cursors} table: at most one row per project, client, and session. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface CaptureCursorRows extends CrudRepository<CaptureCursorRow, Long> {

    Optional<CaptureCursorRow> findByProjectAndRoleAndSessionId(String project, String role, String sessionId);
}
