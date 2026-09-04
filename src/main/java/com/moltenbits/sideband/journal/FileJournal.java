package com.moltenbits.sideband.journal;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/** A journal stored as one file, appended under a sibling lock file. */
@Singleton
class FileJournal implements Journal {

    static final String LOCK_FILE_NAME = "journal.lock";

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final byte NEWLINE = '\n';
    private static final byte[] TERMINATOR_LINE = (TERMINATOR + "\n").getBytes(UTF_8);

    @Override
    public Appended append(Path file, String body) {
        byte[] entry = frame(body);
        Path lockFile = file.resolveSibling(LOCK_FILE_NAME);
        try (JournalLock ignored = JournalLock.acquire(lockFile, LOCK_TIMEOUT);
             FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND)) {
            long start = channel.size();
            ByteBuffer buffer = ByteBuffer.wrap(entry);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            return new Appended(start, start + entry.length);
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to " + file, e);
        }
    }

    private static byte[] frame(String body) {
        String normalized = body.endsWith("\n") ? body : body + "\n";
        byte[] bodyBytes = normalized.getBytes(UTF_8);
        byte[] entry = Arrays.copyOf(bodyBytes, bodyBytes.length + TERMINATOR_LINE.length);
        System.arraycopy(TERMINATOR_LINE, 0, entry, bodyBytes.length, TERMINATOR_LINE.length);
        return entry;
    }

    @Override
    public Read readCompleteFrom(Path file, long offset) {
        if (!Files.exists(file)) {
            return new Read(offset, offset, List.of());
        }
        byte[] tail;
        try (FileChannel channel = FileChannel.open(file, READ)) {
            long size = channel.size();
            if (offset >= size) {
                return new Read(offset, offset, List.of());
            }
            tail = new byte[Math.toIntExact(size - offset)];
            ByteBuffer buffer = ByteBuffer.wrap(tail);
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep filling
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return split(offset, tail);
    }

    /** Walks lines from {@code offset}; every terminator line closes the entry that began after the previous one. */
    private static Read split(long offset, byte[] tail) {
        List<String> entries = new ArrayList<>();
        int entryStart = 0;
        int lineStart = 0;
        while (lineStart < tail.length) {
            int lineEnd = indexOf(tail, NEWLINE, lineStart);
            if (lineEnd < 0) {
                break;
            }
            int nextLine = lineEnd + 1;
            if (isTerminatorLine(tail, lineStart, nextLine)) {
                entries.add(body(tail, entryStart, lineStart));
                entryStart = nextLine;
            }
            lineStart = nextLine;
        }
        return new Read(offset, offset + entryStart, List.copyOf(entries));
    }

    private static boolean isTerminatorLine(byte[] bytes, int from, int to) {
        return Arrays.equals(bytes, from, to, TERMINATOR_LINE, 0, TERMINATOR_LINE.length);
    }

    /** The bytes between the entry start and its terminator line, minus the newline framing added. */
    private static String body(byte[] bytes, int from, int to) {
        int end = to > from && bytes[to - 1] == NEWLINE ? to - 1 : to;
        return new String(bytes, from, end - from, UTF_8);
    }

    private static int indexOf(byte[] bytes, byte target, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
