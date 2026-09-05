package com.moltenbits.sideband.protocol;

/** Raised when entry metadata or a draft violates the protocol's structural rules. */
public class InvalidEntryException extends RuntimeException {

    public InvalidEntryException(String message) {
        super(message);
    }
}
