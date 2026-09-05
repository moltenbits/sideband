package com.moltenbits.sideband.protocol;

import java.util.Collection;
import java.util.Locale;

/** Whether an entry addresses one recipient or several. */
public enum Route implements Wire {

    DIRECT,
    BROADCAST;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Derives the route from the recipient list: more than one recipient is a broadcast. */
    public static Route forRecipients(Collection<ParticipantId> to) {
        return to.size() > 1 ? BROADCAST : DIRECT;
    }
}
