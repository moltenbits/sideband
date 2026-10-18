package com.moltenbits.sideband.store;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

/** The {@code entry_projects} table: written with each entry, never changed. */
@JdbcRepository(dialect = Dialect.SQLITE)
interface EntryProjectRows extends CrudRepository<EntryProjectRow, Long> {

    List<Long> findSeqByProjectAndSeqGreaterThanOrderBySeq(String project, long seq);

    List<Long> findSeqByProjectAndSeqLessThanOrderBySeqDesc(String project, long seq, Pageable pageable);

    boolean existsByProjectAndSeqLessThan(String project, long seq);
}
