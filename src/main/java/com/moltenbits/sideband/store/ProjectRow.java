package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code projects} table: a registered project, its name and its location both unique. */
@MappedEntity("projects")
record ProjectRow(
        @Id @GeneratedValue @Nullable Long id,
        String name,
        String path,
        String root) {
}
