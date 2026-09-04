package com.moltenbits.sideband.journal;

import java.nio.file.Path;

/**
 * The append-only journal shared by every Sideband participant.
 * <p>
 * This is the spike-scope contract: bodies are framed with a terminator line and
 * appended under a lock. Entry metadata, identity, and the full codec arrive later.
 */
public interface Journal {

    /** The file name of the journal inside the state directory. */
    String FILE_NAME = "journal.md";

    /** The line that closes every entry. A reader never returns an entry before it. */
    String TERMINATOR = "<!-- /sideband -->";

    /**
     * Appends one body as a complete entry, serialized against concurrent writers.
     *
     * @return the byte range the entry occupies in the file
     * @throws LockTimeoutException when another writer holds the lock for too long
     */
    Appended append(Path file, String body);

    /**
     * Reads every complete entry that starts at or after {@code offset}.
     * An incomplete trailing entry is never returned; {@link Read#end()} stops before it.
     */
    Read readCompleteFrom(Path file, long offset);
}
