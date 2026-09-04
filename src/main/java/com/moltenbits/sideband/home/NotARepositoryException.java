package com.moltenbits.sideband.home;

import java.nio.file.Path;

/** Raised when a directory is not inside a Git repository, which version one requires. */
public class NotARepositoryException extends RuntimeException {

    public NotARepositoryException(Path directory, String detail) {
        super(directory + " is not inside a Git repository: " + detail.strip());
    }
}
