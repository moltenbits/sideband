package com.moltenbits.sideband.protocol;

import java.util.Locale;

/**
 * The kinds of entry version one recognizes. A request defaults to actionable whoever wrote
 * it, with {@code expects_reply} the authoritative switch either way; the rule is not who may
 * write one but that an agent's actionable entry traces to a human-authored one (section 8.3).
 * An ack is a recipient's statement that it has taken a request up, or is still on it.
 */
public enum MessageType implements Wire {

    REQUEST,
    REPLY,
    STATUS,
    ACK;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
