package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.Wire;

import java.util.Locale;

/** What happened when the writer tried to wake a recipient's host. */
public enum PushOutcome implements Wire {

    /** The host accepted the message and started a turn with it. */
    PUSHED,
    /** The role has never joined in this repository; the entry waits until it does. */
    NO_SESSION,
    /** The host would hold the push for the operator's approval, so it was not sent; the role's own listener delivers. */
    LISTENER_DELIVERS,
    /** The push command failed, which is also how a host that has since gone away shows up; the entry stays pending and the detail says why. */
    FAILED;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
