package com.moltenbits.sideband.waiting;

import com.moltenbits.sideband.journal.Read;

/**
 * The outcome of a wait. {@code read.end()} is where the next wait should start even when
 * the wait timed out, since entries the filter rejected have already been consumed.
 */
public record Waited(Read read, boolean timedOut) {
}
