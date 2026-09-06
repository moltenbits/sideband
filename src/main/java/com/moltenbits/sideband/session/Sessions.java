package com.moltenbits.sideband.session;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Each role's session record, stored beside the journal and replaced atomically under the
 * shared lock. It is an address and a read position, never a record of what was said or
 * done: the journal is the only such record.
 */
public interface Sessions {

    Optional<Session> load(Path stateDirectory, Role role);

    /**
     * Starts the role's session with its watermark at the journal's current end, so that
     * everything already in the journal is what the first {@code pending} presents as
     * predating it. The read position starts there too, unless {@code resume} asks to pick
     * up where the role's previous session left off (or the start of the journal when it
     * never had one), so everything written for it since is shown. A resumed session also
     * means the operator wants what was waiting taken up: a lone request is acted on without
     * asking, several are confirmed first. Any existing record for the role is replaced:
     * one client per role per repository is the operator's convention, not something the
     * executable polices.
     */
    Session join(Path stateDirectory, Role role, String sessionId, boolean resume, @Nullable Delivery delivery);

    default Session join(Path stateDirectory, Role role, String sessionId, boolean resume) {
        return join(stateDirectory, role, sessionId, resume, null);
    }

    default Session join(Path stateDirectory, Role role, String sessionId) {
        return join(stateDirectory, role, sessionId, false, null);
    }

    /** Records that everything ending at or before {@code offset} has been shown to the role. Never moves back. */
    Session advance(Path stateDirectory, Role role, long offset);
}
