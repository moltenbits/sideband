package com.moltenbits.sideband.recipient;

/** Raised when a role already has a live session and activation did not ask to replace it. */
public class SessionConflictException extends RuntimeException {

    private final Session existing;

    public SessionConflictException(Session existing) {
        super("session " + existing.id() + " already owns this role (started " + existing.startedAt()
                + "); pass --replace to supersede it");
        this.existing = existing;
    }

    public Session existing() {
        return existing;
    }
}
