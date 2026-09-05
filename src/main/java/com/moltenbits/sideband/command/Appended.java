package com.moltenbits.sideband.command;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.push.PushResult;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/** What a writer command prints: the entry as stored, plus the push outcome for each client recipient. */
@Serdeable(naming = SnakeCaseStrategy.class)
record Appended(EntryMetadata metadata, String body, long start, long end, List<PushResult> pushes) {

    static Appended of(Entry entry, List<PushResult> pushes) {
        return new Appended(entry.metadata(), entry.body(), entry.start(), entry.end(), pushes);
    }
}
