package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

/** The {@code held_prompts} table: at most one row per role and session. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface HeldPromptRows extends CrudRepository<HeldPromptRow, Long> {

    Optional<HeldPromptRow> findByRoleAndSessionId(String role, String sessionId);
}
