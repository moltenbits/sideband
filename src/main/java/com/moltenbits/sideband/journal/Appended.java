package com.moltenbits.sideband.journal;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/** The byte range one appended entry occupies: {@code [start, end)}. */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Appended(long start, long end) {
}
