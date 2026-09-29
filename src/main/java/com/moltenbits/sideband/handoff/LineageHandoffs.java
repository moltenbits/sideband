package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.ancestry.Ancestry;
import com.moltenbits.sideband.ancestry.EntryIndex;
import com.moltenbits.sideband.ancestry.InvalidLineageException;
import com.moltenbits.sideband.ancestry.Lineage;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Singleton
class LineageHandoffs implements Handoffs {

    private final Journal journal;
    private final Ancestry ancestry;
    private final ObjectMapper json;

    LineageHandoffs(Journal journal, Ancestry ancestry, ObjectMapper json) {
        this.journal = journal;
        this.ancestry = ancestry;
        this.json = json;
    }

    @Override
    public List<Handoff> prepare(Path stateDirectory, List<Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        EntryIndex index = id -> journal.find(stateDirectory, id).map(Entry::metadata);
        List<Handoff> handoffs = new ArrayList<>();
        for (Entry entry : entries) {
            try {
                Lineage lineage = ancestry.trace(entry.metadata(), index);
                handoffs.add(Handoff.of(entry, lineage.effectiveLive(entry.metadata().delivery()), null,
                        lineage instanceof Lineage.Rooted rooted ? rooted : null));
            } catch (InvalidLineageException e) {
                handoffs.add(Handoff.of(entry, DeliveryPolicy.CONFIRM, e.getMessage(), null));
            }
        }
        return handoffs;
    }

    @Override
    public String envelope(Batch batch) {
        try {
            return ENVELOPE_MARKER + "\n" + json.writeValueAsString(batch);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
