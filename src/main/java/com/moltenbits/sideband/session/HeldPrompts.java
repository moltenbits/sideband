package com.moltenbits.sideband.session;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one prompt a role may owe the journal from before it joined. The prompt hook records
 * only while the role has a session, and the prompt that makes a client activate arrives
 * before that session exists: the hook holds it here, verbatim, and {@code join} adopts it
 * as the operator's words. The hook stays the only thing that takes the operator's words
 * from the host; this is where they wait, not a second capture.
 */
public interface HeldPrompts {

    /** Keeps the prompt as the latest one typed into the session before the role joined, replacing any earlier one for the role. */
    void hold(Path stateDirectory, Role role, String sessionId, String prompt);

    /** Forgets whatever is held for the role: the operator's next input was not their own words. */
    void drop(Path stateDirectory, Role role);

    /** The prompt held for the role when it was typed into this session; nothing is changed. */
    Optional<String> held(Path stateDirectory, Role role, String sessionId);

    /**
     * Removes whatever is held for the role and, when it was typed into this session,
     * journals the entry {@code draft} makes of it, both in one transaction: a failure
     * leaves the hold in place for the next join, and success leaves exactly one entry.
     * One held for another session is dropped and not journaled: the operator moved on,
     * and their words in a conversation this join does not continue are not this one's.
     */
    Optional<Entry> adopt(Path stateDirectory, Role role, String sessionId, Function<String, Draft> draft);
}
