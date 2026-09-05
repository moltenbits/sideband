package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.protocol.Wire;

import java.util.Locale;

/** The state of a request this client sent and expects an answer to. */
public enum OutgoingStatus implements Wire {

    PENDING,
    ANSWERED,
    DISMISSED;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
