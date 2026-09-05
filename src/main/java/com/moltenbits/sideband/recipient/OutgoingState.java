package com.moltenbits.sideband.recipient;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A request this client sent. {@code replyIds} records correlated replies without
 * claiming any of them is sufficient; only the parent marks the request answered.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record OutgoingState(OutgoingStatus state, List<String> replyIds, @Nullable OffsetDateTime resolvedAt) {

    public OutgoingState {
        replyIds = List.copyOf(replyIds);
    }

    public static final OutgoingState PENDING = new OutgoingState(OutgoingStatus.PENDING, List.of(), null);

    OutgoingState withReply(String replyId) {
        if (replyIds.contains(replyId)) {
            return this;
        }
        List<String> ids = new ArrayList<>(replyIds);
        ids.add(replyId);
        return new OutgoingState(state, ids, resolvedAt);
    }

    OutgoingState resolved(OutgoingStatus how, OffsetDateTime at) {
        return new OutgoingState(how, replyIds, at);
    }
}
