package com.moltenbits.sideband.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * The push outcome for one recipient role. {@code session} is the host session the pusher
 * actually delivered into, in the identity the role's session record uses (Codex's thread
 * id, Claude Code's session id), set only when the host accepted the text and the pusher
 * knows where it went; it is what the delivery is recorded against, never the role's
 * record looked up afterwards, which another join may have moved in the meantime.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record PushResult(Role role, PushOutcome outcome, @Nullable String detail,
                         @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String session) {

    public PushResult(Role role, PushOutcome outcome, @Nullable String detail) {
        this(role, outcome, detail, null);
    }
}
