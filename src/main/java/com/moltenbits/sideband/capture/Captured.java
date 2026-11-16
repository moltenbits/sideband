package com.moltenbits.sideband.capture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.push.PushResult;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * The stored entry plus how each client recipient was reached.
 *
 * @param unrouted when the operator's words were addressed to a project nobody registered,
 *                 that project: the words were recorded, addressed to the instance they were
 *                 typed into only so they are not lost, and the route they asked for failed
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Captured(EntryMetadata metadata, String body, long seq, List<PushResult> pushes,
                       @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String unrouted) {

    public static Captured of(Entry entry, List<PushResult> pushes) {
        return new Captured(entry.metadata(), entry.body(), entry.seq(), pushes, null);
    }

    public Captured unrouted(@Nullable String project) {
        return new Captured(metadata, body, seq, pushes, project);
    }
}
