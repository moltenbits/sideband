package com.moltenbits.sideband.locking;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-based locks, reentrant within this process so a command that appends an entry
 * and then updates a cursor holds the lock once across both.
 */
@Singleton
class FileLocks implements Locks {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Map<Path, Holder> held = new ConcurrentHashMap<>();

    @Override
    public Lock acquire(Path stateDirectory) {
        Path key = stateDirectory.toAbsolutePath().normalize();
        synchronized (held) {
            Holder holder = held.get(key);
            if (holder != null) {
                holder.depth++;
                return () -> release(key);
            }
            try {
                LockFile file = LockFile.acquire(key.resolve(FILE_NAME), TIMEOUT);
                held.put(key, new Holder(file));
                return () -> release(key);
            } catch (IOException e) {
                throw new UncheckedIOException("could not acquire the lock in " + stateDirectory, e);
            }
        }
    }

    private void release(Path key) throws IOException {
        synchronized (held) {
            Holder holder = held.get(key);
            if (holder == null) {
                return;
            }
            if (--holder.depth == 0) {
                held.remove(key);
                holder.file.close();
            }
        }
    }

    private static final class Holder {
        final LockFile file;
        int depth = 1;

        Holder(LockFile file) {
            this.file = file;
        }
    }
}
