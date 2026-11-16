package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code entry_projects} table: the entry at {@code seq} involves {@code project}. */
@MappedEntity("entry_projects")
record EntryProjectRow(
        @Id @GeneratedValue @Nullable Long id,
        String project,
        long seq) {
}
