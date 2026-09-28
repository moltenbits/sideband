package com.moltenbits.sideband.handoff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moltenbits.sideband.ancestry.Lineage;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import com.moltenbits.sideband.protocol.EntryMetadata;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.time.OffsetDateTime;

/**
 * One entry ready for a recipient's host, with the live policy in force after the
 * delegation-depth check. An entry whose lineage cannot be verified is handed off under
 * {@code confirm} with the problem stated. {@code lineage} appears on an entry the
 * causal-path rule traced to a human-authored entry: its delegation depth and that entry,
 * so a recipient held to {@code confirm} can see why and read what the human asked.
 * {@code pushedAt} appears only in a pending
 * report, on an entry the writer already pushed into the conversation reading the report:
 * the host accepted it then and will surface it, or already has, as a pushed envelope, so
 * that envelope is this same entry arriving by its other path, not a new one.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Handoff(EntryMetadata metadata, String body, long seq,
                      DeliveryPolicy effectiveLive, @Nullable String lineageProblem,
                      @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Lineage.Rooted lineage,
                      @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable OffsetDateTime pushedAt) {

    public Handoff(EntryMetadata metadata, String body, long seq, DeliveryPolicy effectiveLive, @Nullable String lineageProblem) {
        this(metadata, body, seq, effectiveLive, lineageProblem, null, null);
    }

    public static Handoff of(Entry entry, DeliveryPolicy effectiveLive, @Nullable String lineageProblem, @Nullable Lineage.Rooted lineage) {
        return new Handoff(entry.metadata(), entry.body(), entry.seq(), effectiveLive, lineageProblem, lineage, null);
    }

    public Handoff withPushedAt(@Nullable OffsetDateTime at) {
        return new Handoff(metadata, body, seq, effectiveLive, lineageProblem, lineage, at);
    }
}
