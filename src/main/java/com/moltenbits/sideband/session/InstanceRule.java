package com.moltenbits.sideband.session;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Which instance of a role a calling session is, and what joining or following the operator
 * changes, decided from the role's session records alone (REQUIREMENTS.md 9.5a). Nothing
 * here reads or writes storage: the store runs these inside the transaction that loaded the
 * records and saves what they return, so the decision and the write are one unit.
 * <p>
 * Every result keeps the invariant that a live process belongs to at most one record: any
 * record that takes a process takes it from every other record of the role, which keeps its
 * address and pending work but no longer claims to run there.
 */
public interface InstanceRule {

    /**
     * What following the operator's own input decided.
     *
     * @param instance   the instance the calling session is, or null when it holds none
     * @param moved      the instance's address moved to the calling session
     * @param candidates when no instance was decided because several could be the one continued, those; otherwise empty
     * @param records    every record of the role afterwards
     */
    record Followed(@Nullable ParticipantId instance, boolean moved, List<ParticipantId> candidates,
                    SortedMap<ParticipantId, Session> records) {
    }

    /**
     * Who a command is.
     *
     * @param instance the instance it acts as, or null when it must refuse
     * @param records  the role's instances, for the refusal to name
     */
    record Identified(@Nullable ParticipantId instance, List<ParticipantId> records) {
    }

    /**
     * What a join changed.
     *
     * @param records  every record of the role afterwards
     * @param replaced the session the identifier's earlier record named, when the join moved it elsewhere
     */
    record Joined(SortedMap<ParticipantId, Session> records, @Nullable String replaced) {
    }

    /**
     * The hooks' rule for the operator's own input: the record naming the calling session;
     * otherwise the record naming the calling process, as after a clear; otherwise the one
     * record whose client is provably gone, as after a restart. A record found by session
     * takes the caller's process, and one found any other way moves to the calling session.
     */
    Followed follow(Map<ParticipantId, Session> records, String sessionId, @Nullable HostProcess caller);

    /**
     * The commands' rule: an instance only by the record naming the calling session or the
     * calling process, never by inference from what is missing elsewhere, since that proves
     * nothing about who wrote the command. The unnamed instance only when the role has no
     * records at all.
     */
    Identified identify(Role role, Map<ParticipantId, Session> records, @Nullable String sessionId, @Nullable HostProcess caller);

    /**
     * Joining under an identifier: its record is replaced, any other record naming the joining
     * session is removed, since a session holds one instance, and the joining process is taken
     * from any other record naming it.
     */
    Joined join(Map<ParticipantId, Session> records, ParticipantId instance, Session session);

    /** The records of the role's instances among {@code records}. */
    SortedMap<ParticipantId, Session> ofRole(Map<ParticipantId, Session> records, Role role);
}
