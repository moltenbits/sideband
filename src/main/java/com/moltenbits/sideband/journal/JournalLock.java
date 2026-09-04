package com.moltenbits.sideband.journal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * An exclusive lock taken by atomically creating a file that records the owner's PID.
 * <p>
 * A lock whose owning process is no longer alive is treated as stale and reclaimed.
 * A lock whose contents cannot be read is reclaimed only after a grace period.
 */
final class JournalLock implements AutoCloseable {

    private static final Duration RETRY_INTERVAL = Duration.ofMillis(10);
    private static final Duration UNREADABLE_GRACE = Duration.ofSeconds(30);

    private final Path lockFile;

    private JournalLock(Path lockFile) {
        this.lockFile = lockFile;
    }

    static JournalLock acquire(Path lockFile, Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        byte[] owner = Long.toString(ProcessHandle.current().pid()).getBytes(UTF_8);
        while (true) {
            try {
                Files.write(lockFile, owner, CREATE_NEW, WRITE);
                return new JournalLock(lockFile);
            } catch (FileAlreadyExistsException held) {
                if (isStale(lockFile)) {
                    Files.deleteIfExists(lockFile);
                    continue;
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new LockTimeoutException(lockFile, timeout);
                }
                pause();
            }
        }
    }

    private static boolean isStale(Path lockFile) throws IOException {
        String contents;
        FileTime modified;
        try {
            contents = Files.readString(lockFile, UTF_8).strip();
            modified = Files.getLastModifiedTime(lockFile);
        } catch (NoSuchFileException released) {
            return false;
        }
        try {
            long pid = Long.parseLong(contents);
            return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
        } catch (NumberFormatException unreadable) {
            return modified.toInstant().plus(UNREADABLE_GRACE).isBefore(Instant.now());
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the journal lock", e);
        }
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(lockFile);
    }
}
