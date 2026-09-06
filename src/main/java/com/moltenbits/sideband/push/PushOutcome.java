package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Wire;

import java.util.Locale;

/** What happened when the writer tried to wake a recipient's host. */
public enum PushOutcome implements Wire {

    /** The host accepted the message and started a turn with it. */
    PUSHED,
    /** The role has never activated in this repository; the entry waits as backlog. */
    NO_SESSION,
    /** The role's recorded session process is gone; the entry waits as backlog. */
    SESSION_DEAD,
    /** The host has no push command; the role's own listener delivers. */
    LISTENER_DELIVERS,
    /** The push command failed; the entry stays pending and the detail says why. */
    FAILED;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
