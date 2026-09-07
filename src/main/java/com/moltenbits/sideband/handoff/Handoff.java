package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import com.moltenbits.sideband.protocol.EntryMetadata;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * One entry ready for a recipient's host, with the live policy in force after the
 * delegation-depth check. An entry whose lineage cannot be verified is handed off under
 * {@code confirm} with the problem stated.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Handoff(EntryMetadata metadata, String body, long seq,
                      DeliveryPolicy effectiveLive, @Nullable String lineageProblem) {

    public static Handoff of(Entry entry, DeliveryPolicy effectiveLive, @Nullable String lineageProblem) {
        return new Handoff(entry.metadata(), entry.body(), entry.seq(), effectiveLive, lineageProblem);
    }
}
