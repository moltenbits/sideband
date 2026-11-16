package com.moltenbits.sideband.session;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one prompt a session may owe the journal from before it held an instance. The prompt
 * hook records only for a session that holds one, and the prompt that makes a client
 * activate arrives before the join: the hook holds it here, verbatim, and {@code join}
 * adopts it as the operator's words. Several sessions of a client may be waiting to join at
 * once, so each session's hold is its own, kept per project, and the name the session joins
 * under is not needed until then. The hook stays the only thing that takes the operator's words from the
 * host; this is where they wait, not a second capture.
 */
public interface HeldPrompts {

    /**
     * Keeps the prompt as the latest one typed into the session before it joined, replacing
     * any earlier one of that session's, with the turn it started as the host names it, so
     * the adopted entry can be that turn's cause.
     */
    void hold(Path stateDirectory, String project, Role role, String sessionId, String prompt, @Nullable String turn);

    /** {@link #hold} from a host that names no turn. */
    default void hold(Path stateDirectory, String project, Role role, String sessionId, String prompt) {
        hold(stateDirectory, project, role, sessionId, prompt, null);
    }

    /** Forgets whatever the session holds: the operator's next input there was not their own words. */
    void drop(Path stateDirectory, String project, Role role, String sessionId);

    /** The prompt the session holds for its role; nothing is changed. */
    Optional<String> held(Path stateDirectory, String project, Role role, String sessionId);


    /**
     * A held prompt journaled by its adoption.
     *
     * @param turn the turn that prompt started, from the same hold: never another prompt's
     */
    record Adopted(Entry entry, @Nullable String turn) {
    }

    /**
     * Removes the session's hold and journals the entry {@code draft} makes of it, both in
     * one transaction: a failure leaves the hold in place for the next join, and success
     * leaves exactly one entry. Other sessions' holds are left alone.
     */
    Optional<Adopted> adopt(Path stateDirectory, String project, Role role, String sessionId, Function<String, Draft> draft);
}
