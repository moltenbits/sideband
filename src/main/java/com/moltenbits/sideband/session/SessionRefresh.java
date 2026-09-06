package com.moltenbits.sideband.session;

/** Outcome of checking that a caller owns a role's session, refreshing a stale record when it does. */
public enum SessionRefresh {
    READY,
    REFRESHED,
    NOT_ACTIVE,
    SESSION_MISMATCH,
    CALLER_UNAVAILABLE
}
