package com.moltenbits.sideband.journal;

/** Generates globally unique, stable message identifiers. */
public interface MessageIds {

    String next();
}
