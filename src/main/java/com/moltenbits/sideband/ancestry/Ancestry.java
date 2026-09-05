package com.moltenbits.sideband.ancestry;

import com.moltenbits.sideband.protocol.EntryMetadata;

/**
 * Enforces that agents never originate actionable work between themselves.
 * <p>
 * An agent-authored entry with {@code expects_reply} true that addresses a client must
 * trace to a human-authored entry: follow {@code caused_by} when present, otherwise
 * {@code reply_to}. Only {@code caused_by} links count toward delegation depth; reply
 * iteration is unbounded.
 */
public interface Ancestry {

    /**
     * @throws InvalidLineageException when the rule applies and an ancestor is missing or the links cycle
     */
    Lineage trace(EntryMetadata entry, EntryIndex index);
}
