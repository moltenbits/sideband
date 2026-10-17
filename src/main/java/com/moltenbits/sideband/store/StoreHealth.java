package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * What {@code doctor} says about the database, never including a body.
 *
 * @param path      the database file
 * @param bytes     its size, without the write-ahead log
 * @param entries   how many entries it holds
 * @param integrity    SQLite's own verdict: {@code ok}, or the first problems it found
 * @param schema       the migration the database is at, {@code PRAGMA user_version}
 * @param migratedFrom the migration it was at when this command opened it, when opening it migrated it; otherwise null
 * @param supported    the latest migration this executable carries; a schema above it was migrated by a newer Sideband
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record StoreHealth(String path, long bytes, long entries, String integrity, int schema,
                          @Nullable Integer migratedFrom, int supported) {
}
