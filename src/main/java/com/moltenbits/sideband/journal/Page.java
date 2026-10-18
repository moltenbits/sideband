package com.moltenbits.sideband.journal;

import java.util.List;

/**
 * A page of the journal read back from a position, newest first.
 *
 * @param entries the entries, newest first
 * @param older   whether the journal holds entries older than the last one here
 */
public record Page(List<Entry> entries, boolean older) {

    public Page {
        entries = List.copyOf(entries);
    }
}
