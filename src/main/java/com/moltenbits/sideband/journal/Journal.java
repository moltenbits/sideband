package com.moltenbits.sideband.journal;

import com.moltenbits.sideband.protocol.Draft;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The append-only journal shared by every Sideband participant: every entry ever written,
 * in the order it was written. Each entry has a position, the sequence number the store
 * assigned when it was appended; positions only grow, so a reader that remembers the last
 * one it saw asks for everything after it.
 */
public interface Journal {

    /** The protocol version this implementation writes and the only one it reads. */
    String PROTOCOL_VERSION = "v1";

    /**
     * Assigns an identifier and timestamp to the draft and appends it as one complete
     * entry, serialized against concurrent writers.
     *
     * @throws com.moltenbits.sideband.store.BusyException when another writer holds the store for too long
     */
    Entry append(Path stateDirectory, Draft draft);

    /** Every entry whose position is greater than {@code position}, in order. */
    Read readAfter(Path stateDirectory, long position);

    /** The entry with the given identifier, when it exists. */
    Optional<Entry> find(Path stateDirectory, String id);

    /** The position of the last entry, or zero when there is none. */
    long end(Path stateDirectory);
}
