package com.moltenbits.sideband.host;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.Instant;
import java.util.Objects;

/**
 * A client's host process: its id together with when it started, so that an id the system
 * has since given to another program is never taken for the client (REQUIREMENTS.md 9.5a).
 * The start time is kept to the second, which is as far as every platform reports it alike.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record HostProcess(long pid, Instant startedAt) {

    public HostProcess {
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
