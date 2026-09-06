package com.moltenbits.sideband.session;

import com.moltenbits.sideband.protocol.Wire;

/**
 * How entries reach a Claude Code session, decided once when it joins so that writers and
 * the session agree for the whole session: pushed into it over its inbox socket, or read by
 * the listener the session runs. Codex is always pushed to and records nothing here.
 */
public enum Delivery implements Wire {

    PUSH("push"),

    LISTEN("listen");

    private final String id;

    Delivery(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}
