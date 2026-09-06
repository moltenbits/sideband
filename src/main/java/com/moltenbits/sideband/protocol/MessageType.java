package com.moltenbits.sideband.protocol;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of entry version one recognizes. A request is actionable whoever wrote it; the
 * rule is not who may write one but that every chain of requests and replies leads back to
 * a request a human wrote (section 8.3).
 */
public enum MessageType implements Wire {

    REQUEST,
    REPLY,
    STATUS;

    /** Journals written before the human-only type was folded into request. */
    private static final String LEGACY_INSTRUCTION = "instruction";

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** An identifier this type also accepts on read, for entries written under an earlier name. */
    public static Optional<MessageType> fromLegacyId(String id) {
        return LEGACY_INSTRUCTION.equals(id) ? Optional.of(REQUEST) : Optional.empty();
    }
}
