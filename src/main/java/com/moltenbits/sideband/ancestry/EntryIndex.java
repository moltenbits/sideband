package com.moltenbits.sideband.ancestry;

import com.moltenbits.sideband.protocol.EntryMetadata;

import java.util.Optional;

/** Looks up stored entry metadata by identifier. */
@FunctionalInterface
public interface EntryIndex {

    Optional<EntryMetadata> find(String id);
}
