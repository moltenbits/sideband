package com.moltenbits.sideband.locking;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;

@Singleton
class FileLocks implements Locks {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Override
    public Lock acquire(Path stateDirectory) {
        try {
            return LockFile.acquire(stateDirectory.resolve(FILE_NAME), TIMEOUT);
        } catch (IOException e) {
            throw new UncheckedIOException("could not acquire the lock in " + stateDirectory, e);
        }
    }
}
