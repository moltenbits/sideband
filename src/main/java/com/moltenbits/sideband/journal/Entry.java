package com.moltenbits.sideband.journal;

import com.moltenbits.sideband.protocol.EntryMetadata;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * A complete entry read from or written to the journal.
 *
 * @param metadata the stored metadata
 * @param body     the exact body text
 * @param seq      the entry's position: the sequence number the store assigned on append
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Entry(EntryMetadata metadata, String body, long seq) {
}
