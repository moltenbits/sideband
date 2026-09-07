package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

/**
 * A host's native way of starting a new turn in an existing session. Each pusher resolves
 * its own address: how a host is found is the host's business, not the writer's.
 */
public interface HostPusher {

    Role role();

    /**
     * @param stateDirectory the repository's Sideband state, from which the recipient is found
     * @return the outcome, {@link PushOutcome#PUSHED} when the host accepted the text,
     *         {@link PushOutcome#NO_SESSION} when no session of the role could be found
     */
    PushResult push(Path stateDirectory, String text);
}
