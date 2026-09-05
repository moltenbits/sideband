package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Client-local state for one role, stored beside the journal. Every mutation is atomic
 * and serialized on the shared lock. State advances only on explicit calls: a wake
 * notification never advances it by itself.
 */
public interface RecipientState {

    Cursor load(Path stateDirectory, Role role);

    /**
     * Establishes the startup watermark at the last complete entry and records the
     * session. Unresolved entries addressed to the role at or before the watermark are
     * returned as backlog and marked seen. Outgoing requests are reconciled from the
     * journal so a crash between append and cursor update loses nothing.
     *
     * @throws SessionConflictException when another live session owns the role and {@code replace} is false
     */
    Activation activate(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid, boolean replace);

    /**
     * Checks ownership under the shared lock. For the same conversation only, replaces a dead
     * recorded host with the caller's identified, living process. Preserves the activation
     * watermark, timestamps and all incoming/outgoing state; never activates or replaces a role.
     */
    SessionRefresh refreshSession(Path stateDirectory, Role role, String sessionId, @Nullable Long parentPid);

    /** Records that the host accepted the handoff, and correlates replies to pending outgoing requests. */
    Cursor markDelivered(Path stateDirectory, Role role, List<String> ids);

    Cursor markSeen(Path stateDirectory, Role role, List<String> ids);

    Cursor resolve(Path stateDirectory, Role role, List<String> ids, Resolution resolution);

    /** Registers a request this role sent and expects an answer to. */
    Cursor registerOutgoing(Path stateDirectory, Role role, String requestId);

    Cursor resolveOutgoing(Path stateDirectory, Role role, List<String> ids, OutgoingStatus status);

    Pending pending(Path stateDirectory, Role role);

    /** Whether {@code entry} still needs the role's attention: addressed to it and unresolved. */
    boolean isOpen(Cursor cursor, Entry entry);
}
