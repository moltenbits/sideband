package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

/** The {@code projects} table. Rows are only ever inserted. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface ProjectRows extends CrudRepository<ProjectRow, Long> {

    Optional<ProjectRow> findByPath(String path);

    Optional<ProjectRow> findByName(String name);
}
