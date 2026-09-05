package com.moltenbits.sideband.config;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * Who the local human is. The identifier is protocol identity ({@code human:<id>});
 * the display name is presentation only.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record HumanIdentity(String id, String displayName) {
}
