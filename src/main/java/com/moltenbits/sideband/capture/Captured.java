package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.push.PushResult;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/** The stored entry plus how each client recipient was reached. */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Captured(EntryMetadata metadata, String body, long seq, List<PushResult> pushes) {

    public static Captured of(Entry entry, List<PushResult> pushes) {
        return new Captured(entry.metadata(), entry.body(), entry.seq(), pushes);
    }
}
