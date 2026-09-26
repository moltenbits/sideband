package com.moltenbits.sideband.session;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one prompt a session may owe the journal from before it held an instance. The prompt
 * hook records only for a session that holds one, and the prompt that makes a client
 * activate arrives before the join: the hook holds it here, verbatim, and {@code join}
 * adopts it as the operator's words. Several sessions of a role may be waiting to join at
 * once, so each session's hold is its own, and the name the session joins under is not
 * needed until then. The hook stays the only thing that takes the operator's words from the
 * host; this is where they wait, not a second capture.
 */
public interface HeldPrompts {

    /** Keeps the prompt as the latest one typed into the session before it joined, replacing any earlier one of that session's. */
    void hold(Path stateDirectory, Role role, String sessionId, String prompt);

    /** Forgets whatever the session holds: the operator's next input there was not their own words. */
    void drop(Path stateDirectory, Role role, String sessionId);

    /** The prompt the session holds for its role; nothing is changed. */
    Optional<String> held(Path stateDirectory, Role role, String sessionId);

    /**
     * Removes the session's hold and journals the entry {@code draft} makes of it, both in
     * one transaction: a failure leaves the hold in place for the next join, and success
     * leaves exactly one entry. Other sessions' holds are left alone.
     */
    Optional<Entry> adopt(Path stateDirectory, Role role, String sessionId, Function<String, Draft> draft);
}
