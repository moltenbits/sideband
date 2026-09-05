package com.moltenbits.sideband.journal;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/** A parse problem at a byte offset. Diagnostics never include body text. */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Diagnostic(long offset, String reason) {
}
