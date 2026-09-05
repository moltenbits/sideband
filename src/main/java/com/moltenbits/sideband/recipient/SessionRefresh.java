package com.moltenbits.sideband.recipient;

/** Outcome of checking an existing conversation and refreshing its dead host process. */
public enum SessionRefresh {
    READY,
    REFRESHED,
    NOT_ACTIVE,
    SESSION_MISMATCH,
    CALLER_UNAVAILABLE
}
