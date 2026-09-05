package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.protocol.Wire;

/** How an incoming entry stopped being pending. */
public enum Resolution implements Wire {

    /** The parent acted on it. */
    ACTED("acted"),
    /** The human dismissed it without action. */
    DISMISSED("dismissed"),
    /** A non-actionable entry was shown to the parent. */
    PRESENTED("presented"),
    /** A human entry the originating client already handled on the turn it was typed. */
    ORIGINATING_TURN("originating-turn");

    private final String id;

    Resolution(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}
