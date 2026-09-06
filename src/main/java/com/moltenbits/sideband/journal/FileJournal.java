package com.moltenbits.sideband.journal;

import com.moltenbits.sideband.locking.Lock;
import com.moltenbits.sideband.locking.Locks;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.EntryMetadata;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/** A journal stored as one file in the state directory, appended under the shared lock. */
@Singleton
class FileJournal implements Journal {

    private final EntryCodec codec;
    private final Clock clock;
    private final MessageIds ids;
    private final Locks locks;

    FileJournal(EntryCodec codec, Clock clock, MessageIds ids, Locks locks) {
        this.codec = codec;
        this.clock = clock;
        this.ids = ids;
        this.locks = locks;
    }

    @Override
    public Entry append(Path file, Draft draft) {
        EntryMetadata metadata = new EntryMetadata(
                ids.next(),
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS),
                draft.from(), draft.via(), draft.to(), draft.type(), draft.route(),
                draft.replyTo(), draft.causedBy(), draft.expectsReply(), draft.heartbeatSeconds(), draft.delivery(),
                EntryCodec.bodyLength(draft.body()));
        byte[] encoded = codec.encode(metadata, draft.body());
        try (Lock ignored = locks.acquire(file.getParent());
             FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND)) {
            long start = closeFragment(file, channel);
            writeFully(channel, encoded);
            writeFully(channel, EntryCodec.SEPARATOR);
            channel.force(true);
            return new Entry(metadata, draft.body(), start, start + encoded.length);
        } catch (IOException e) {
            throw new UncheckedIOException("could not append to " + file, e);
        }
    }

    /**
     * A crashed writer can leave one incomplete fragment at the tail. Under the lock, close it
     * with an abort marker so readers can move past it. Returns the offset the new entry starts at.
     */
    private long closeFragment(Path file, FileChannel channel) throws IOException {
        long size = channel.size();
        if (size == 0) {
            return 0;
        }
        Read read = readCompleteFrom(file, 0);
        if (read.end() >= size) {
            return size;
        }
        byte[] closer = endsWithNewline(file, size)
                ? EntryCodec.ABORT_LINE
                : concat(new byte[] {'\n'}, EntryCodec.ABORT_LINE);
        writeFully(channel, closer);
        return size + closer.length;
    }

    private static boolean endsWithNewline(Path file, long size) throws IOException {
        try (FileChannel reader = FileChannel.open(file, READ)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            reader.position(size - 1);
            reader.read(last);
            return last.get(0) == '\n';
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    @Override
    public Read readCompleteFrom(Path file, long offset) {
        if (!Files.exists(file)) {
            return Read.empty(offset);
        }
        byte[] tail;
        try (FileChannel channel = FileChannel.open(file, READ)) {
            long size = channel.size();
            if (offset >= size) {
                return Read.empty(offset);
            }
            tail = readTail(channel, offset, size);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return codec.parse(tail, offset);
    }

    private static byte[] readTail(FileChannel channel, long from, long to) throws IOException {
        byte[] tail = new byte[Math.toIntExact(to - from)];
        ByteBuffer buffer = ByteBuffer.wrap(tail);
        channel.position(from);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // keep filling
        }
        return tail;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
