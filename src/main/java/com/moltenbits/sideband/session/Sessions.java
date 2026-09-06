package com.moltenbits.sideband.session;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Each role's session record, stored beside the journal and replaced atomically under the
 * shared lock. It is identity and a read position, never a record of what was said or done:
 * the journal is the only such record.
 */
public interface Sessions {

    Optional<Session> load(Path stateDirectory, Role role);

    /**
     * Starts the role's session with its watermark at the journal's current end, so that
     * everything already in the journal is what the first {@code pending} presents as
     * predating it. The read position starts there too, unless {@code resume} asks to pick
     * up where the role's previous session left off (or the start of the journal when it
     * never had one), so everything written for it since is shown. A resumed session also
     * means the operator wants requests that predate it acted on, not confirmed first.
     *
     * @throws SessionConflictException when another live session owns the role and {@code replace} is false
     */
    Session join(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid, boolean replace, boolean resume);

    default Session join(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid, boolean replace) {
        return join(stateDirectory, role, sessionId, parentPid, replace, false);
    }

    /**
     * Checks that a caller owns the role's session. The same conversation with a dead
     * recorded process, or the same host process with a new conversation id, is refreshed
     * in place; anything else is refused. Never joins a role.
     */
    SessionRefresh refresh(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid);

    /** Records that everything ending at or before {@code offset} has been shown to the role. Never moves back. */
    Session advance(Path stateDirectory, Role role, long offset);
}
