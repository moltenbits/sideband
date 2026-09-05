package com.moltenbits.sideband.protocol;

import java.util.Locale;

/** The kinds of entry version one recognizes. */
public enum MessageType implements Wire {

    INSTRUCTION,
    REQUEST,
    REPLY,
    STATUS;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
