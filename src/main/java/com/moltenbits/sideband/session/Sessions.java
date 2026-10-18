package com.moltenbits.sideband.session;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedMap;

/**
 * Each instance's session record, stored beside the journal and changed atomically under the
 * shared lock, scoped to its project by the instance's identifier. It is an address and a read position, never a record of what was said or
 * done: the journal is the only such record. Which instance a calling session is follows
 * {@link InstanceRule}, applied inside the transaction that reads the records.
 */
public interface Sessions {

    Optional<Session> load(Path stateDirectory, ParticipantId instance);

    /** Every record in the project, by participant. Empty when there is no store. */
    SortedMap<ParticipantId, Session> all(Path stateDirectory, String project);

    /** The records of one client's instances in the project, by participant. */
    SortedMap<ParticipantId, Session> records(Path stateDirectory, String project, Role client);

    /**
     * Starts the instance's session, in {@code client}, with its watermark at the journal's
     * current end, so that everything already in the journal is what the first {@code pending}
     * presents as predating it. The read position starts there too, unless {@code resume} asks
     * to pick up where the instance's previous session left off (or the start of the journal
     * when it never had one), so everything written for it since is shown. A resumed session
     * also means the operator wants what was waiting taken up: a lone request is acted on
     * without asking, several are confirmed first. An earlier record for the instance is
     * replaced, whichever client it was. Any other record of the client naming the joining
     * session is removed, and the joining process is taken from any other record naming it.
     */
    Joining join(Path stateDirectory, ParticipantId instance, Role client, String sessionId, @Nullable HostProcess process,
                 boolean resume);

    default Joining join(Path stateDirectory, ParticipantId instance, Role client, String sessionId) {
        return join(stateDirectory, instance, client, sessionId, null, false);
    }

    /** Records that everything ending at or before {@code offset} has been shown to the instance. Never moves back. */
    Session advance(Path stateDirectory, ParticipantId instance, long offset);

    /**
     * Follows the operator's own input from a host session in a project: which instance it is,
     * by the hooks' rule, with whatever that rule moved written in the same transaction. This
     * is how a record follows the operator when a client starts a new conversation in place,
     * as a clear does, since the old conversation may live on and pushes addressed to it would
     * run there unseen. Writes nothing when there is no store.
     */
    InstanceRule.Followed follow(Path stateDirectory, String project, Role client, String sessionId, @Nullable HostProcess caller);

    /** Who a command from a host session in a project is, by the commands' rule. Reads only. */
    InstanceRule.Identified identify(Path stateDirectory, String project, Role client, @Nullable String sessionId,
                                     @Nullable HostProcess caller);
}
