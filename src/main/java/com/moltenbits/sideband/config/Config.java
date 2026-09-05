package com.moltenbits.sideband.config;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/** The repository's local Sideband configuration, stored as {@code config.json} in the state directory. */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Config(int schema, HumanIdentity human) {

    public static final int SCHEMA = 1;
}
