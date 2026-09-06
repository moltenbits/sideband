package com.moltenbits.sideband.session;

/** Another live session already owns the role in this repository. */
public class SessionConflictException extends RuntimeException {

    public SessionConflictException(Session existing) {
        super("session " + existing.id() + " (process " + existing.parentPid() + ") already owns this role here; pass --replace to supersede it");
    }
}
