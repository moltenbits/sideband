package com.moltenbits.sideband.ancestry;

/** Raised when an actionable agent-to-agent entry has no valid path to a human-authored entry. */
public class InvalidLineageException extends RuntimeException {

    public InvalidLineageException(String message) {
        super(message);
    }
}
