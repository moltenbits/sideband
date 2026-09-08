package com.moltenbits.sideband.handoff;

import com.moltenbits.sideband.journal.Entry;

import java.nio.file.Path;
import java.util.List;

/** Prepares entries for a host, applying the ancestry rules the recipient must honor. */
public interface Handoffs {

    /** The marker every delivered envelope begins with; capture hooks skip text that starts with it. */
    String ENVELOPE_MARKER = "[Sideband message]";

    List<Handoff> prepare(Path stateDirectory, List<Entry> entries);

    /** The text a host receives: the marker, a newline, then the batch as JSON. */
    String envelope(Batch batch);
}
