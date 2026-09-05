package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Session;

/** A host's native command for starting a new turn in an existing session. */
public interface HostPusher {

    Role role();

    /**
     * @return the outcome, {@link PushOutcome#PUSHED} when the host accepted the text
     */
    PushResult push(Session session, String text);
}
