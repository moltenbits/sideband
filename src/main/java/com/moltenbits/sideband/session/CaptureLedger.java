package com.moltenbits.sideband.session;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the hooks have recorded of one client session's own words, kept as one versioned
 * unit: where reading its transcript stopped, and a receipt for each turn. The capture that
 * reads a transcript decides outside any transaction and commits here only if nothing
 * changed meanwhile, so two hooks firing for the same turn record it once.
 */
public interface CaptureLedger {

    /** Where the last capture stopped reading a session's transcript, at a line end, inside {@code turn}. */
    record Cursor(Path transcript, long position, @Nullable String turn) {
    }

    /** How a turn's words were recorded. A turn is never recorded both ways. */
    enum Mode {
        /** Nothing of the turn's has been recorded yet. */
        NONE,
        /** From the transcript, once each, in order. */
        TRANSCRIPT,
        /** Its final message alone, from the hook payload, because the transcript could not be read. */
        PAYLOAD
    }

    /**
     * A turn's receipt.
     *
     * @param promptEntryId the operator's prompt that started it, as recorded, when one did
     * @param finalCaptured its final message has been recorded
     */
    record Turn(String id, @Nullable String promptEntryId, Mode mode, boolean finalCaptured) {

        public static Turn untouched(String id) {
            return new Turn(id, null, Mode.NONE, false);
        }
    }

    /**
     * A session's capture state as it was read.
     *
     * @param version zero when nothing is stored for the session
     * @param cursor  null when no capture has a place to resume from
     */
    record State(long version, @Nullable Cursor cursor, Map<String, Turn> turns) {

        public State {
            turns = Map.copyOf(turns);
        }

        public Turn turn(String id) {
            return turns.getOrDefault(id, Turn.untouched(id));
        }
    }

    State load(Path stateDirectory, Role client, String sessionId);

    /** Which of {@code messageIds}, the identities transcript records carry, were recorded for the session. */
    Set<String> recorded(Path stateDirectory, Role client, String sessionId, Collection<String> messageIds);

    /**
     * Commits a decision made from {@code read}, in one transaction and only if the session's
     * state is still at {@code read.version()} and, when {@code author} is given, a record
     * names the session as that instance: the drafts are journaled in order, the messages
     * they hold kept as recorded by {@code messageIds}, the cursor replaced, the given turns
     * written, and the version advanced. Empty, with nothing written, when either check fails.
     */
    Optional<List<Entry>> commit(Path stateDirectory, Role client, String sessionId, @Nullable HostProcess caller,
                                 State read, @Nullable ParticipantId author, List<Draft> drafts,
                                 Collection<String> messageIds, @Nullable Cursor cursor, Collection<Turn> turns);
}
