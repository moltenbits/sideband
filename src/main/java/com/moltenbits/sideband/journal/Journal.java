package com.moltenbits.sideband.journal;

import com.moltenbits.sideband.protocol.Draft;

import java.nio.file.Path;

/**
 * The append-only journal shared by every Sideband participant.
 * <p>
 * Each entry is an HTML-comment metadata block, a generated heading, the body, and a
 * closing marker. The body's byte length is recorded in the metadata, so a body may
 * contain anything, including another complete entry.
 */
public interface Journal {

    /** The file name of the journal inside the state directory. */
    String FILE_NAME = "journal.md";

    /** The protocol version this implementation writes and the only one it reads. */
    String PROTOCOL_VERSION = "v1";

    /**
     * Assigns an identifier and timestamp to the draft and appends it as one complete
     * entry, serialized against concurrent writers. An incomplete fragment left by a
     * crashed writer is closed with an abort marker first; existing bytes are never changed.
     *
     * @throws com.moltenbits.sideband.locking.LockTimeoutException when another writer holds the lock for too long
     */
    Entry append(Path file, Draft draft);

    /**
     * Reads every complete, well-formed entry that starts at or after {@code offset}.
     * Malformed entries are reported as diagnostics and skipped. An incomplete trailing
     * entry is never returned; {@link Read#end()} stops before it.
     */
    Read readCompleteFrom(Path file, long offset);
}
