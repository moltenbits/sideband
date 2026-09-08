package com.moltenbits.sideband.store;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * What {@code doctor} says about the database, never including a body.
 *
 * @param path      the database file
 * @param bytes     its size, without the write-ahead log
 * @param entries   how many entries it holds
 * @param integrity SQLite's own verdict: {@code ok}, or the first problems it found
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record StoreHealth(String path, long bytes, long entries, String integrity) {
}
