package com.moltenbits.sideband.protocol;

import java.util.Locale;

/**
 * The kinds of entry version one recognizes. A request is actionable whoever wrote it; the
 * rule is not who may write one but that every chain of requests and replies leads back to
 * a request a human wrote (section 8.3).
 */
public enum MessageType implements Wire {

    REQUEST,
    REPLY,
    STATUS;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
