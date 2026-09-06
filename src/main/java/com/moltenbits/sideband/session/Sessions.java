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
     * Starts the role's session with its watermark and read position at the journal's
     * current end. Everything already in the journal is what the session's first
     * {@code pending} presents as predating it.
     *
     * @throws SessionConflictException when another live session owns the role and {@code replace} is false
     */
    Session activate(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid, boolean replace);

    /**
     * Checks that a caller owns the role's session. The same conversation with a dead
     * recorded process, or the same host process with a new conversation id, is refreshed
     * in place; anything else is refused. Never activates a role.
     */
    SessionRefresh refresh(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid);

    /** Records that everything ending at or before {@code offset} has been shown to the role. Never moves back. */
    Session advance(Path stateDirectory, Role role, long offset);
}
