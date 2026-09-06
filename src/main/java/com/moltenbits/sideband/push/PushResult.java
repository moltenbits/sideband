package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/** The push outcome for one recipient role. */
@Serdeable(naming = SnakeCaseStrategy.class)
public record PushResult(Role role, PushOutcome outcome, @Nullable String detail) {
}
