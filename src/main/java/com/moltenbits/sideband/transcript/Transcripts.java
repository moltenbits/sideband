package com.moltenbits.sideband.transcript;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * The discussion in a client's own transcript: what the agent said to the operator, with
 * its reasoning, tool calls and tool output left out (REQUIREMENTS.md 7.3). Each host
 * writes its transcript as JSON lines in a format it does not document, so this reads only
 * the records it recognizes and skips the rest. A reader works in byte offsets at line
 * ends, and never reads a line the host has not finished writing.
 */
public interface Transcripts {

    /**
     * The discussion in the complete lines after {@code offset}. Neither Claude Code's
     * assistant records nor Codex's response items name their turn, so the caller passes
     * the turn the reader is in at {@code offset}, as an earlier read returned it.
     *
     * @throws IOException when the transcript cannot be read, a missing one included
     */
    Excerpt read(Role client, Path transcript, long offset, @Nullable String turn) throws IOException;

    /** The offset of the line that starts the turn the host identifies as {@code turn}; empty when there is none. */
    OptionalLong start(Role client, Path transcript, String turn) throws IOException;

    /** The offset just past the last complete line. */
    long end(Path transcript) throws IOException;
}
