package com.moltenbits.sideband.session;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Which entries a role's host has accepted for the session holding the role: the delivery
 * state of section 11.3, advanced when the host takes the envelope and never by anything
 * the model does with it. A push lands in the host's own queue, which may hold it until
 * the conversation is idle, and in the meantime the same entry can be read through
 * {@code pending}; this record is what lets that read say the entry is also on its way
 * in. It is scoped to the session pushed into, so a session that replaced it, as after a
 * restart or a clear, finds nothing in flight and is shown everything again.
 */
public interface Deliveries {

    /** Records that the host holding {@code sessionId} for the role accepted the entry at {@code seq}. */
    void record(Path stateDirectory, long seq, Role role, String sessionId);

    /** The entries pushed into the given session, by position, with when the host accepted each. */
    Map<Long, OffsetDateTime> pushedInto(Path stateDirectory, Role role, String sessionId);
}
