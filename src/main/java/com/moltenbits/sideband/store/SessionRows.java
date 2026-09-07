package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

/** The {@code sessions} table: at most one row per role. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface SessionRows extends CrudRepository<SessionRow, String> {
}
