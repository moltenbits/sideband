package com.moltenbits.sideband.transcript;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Reads a transcript a line at a time by each client's {@link Format}, counting bytes so offsets stay exact. */
@Singleton
class JsonLinesTranscripts implements Transcripts {

    /** How much of the file {@link #end} reads at a time. */
    private static final int WINDOW = 8192;

    private final Map<Role, Format> formats;

    JsonLinesTranscripts(List<Format> formats) {
        this.formats = formats.stream().collect(Collectors.toMap(Format::client, Function.identity()));
    }

    @Override
    public Excerpt read(Role client, Path transcript, long offset, @Nullable String turn) throws IOException {
        Format format = format(client);
        List<Message> messages = new ArrayList<>();
        String current = turn;
        long end = offset;
        try (Lines lines = new Lines(transcript, offset)) {
            for (Line line = lines.next(); line != null; line = lines.next()) {
                Format.Reading reading = format.read(line.text());
                if (reading.turnStarted() != null) {
                    current = reading.turnStarted();
                }
                if (reading.said() != null) {
                    messages.add(new Message(current, line.position(), reading.said(), reading.endsTurn(), reading.id()));
                }
                end = line.end();
            }
        }
        return new Excerpt(messages, end, current);
    }

    @Override
    public OptionalLong start(Role client, Path transcript, String turn) throws IOException {
        Format format = format(client);
        try (Lines lines = new Lines(transcript, 0)) {
            for (Line line = lines.next(); line != null; line = lines.next()) {
                if (format.mayStart(line.text(), turn) && turn.equals(format.read(line.text()).turnStarted())) {
                    return OptionalLong.of(line.position());
                }
            }
        }
        return OptionalLong.empty();
    }

    /** Searches back from the end of the file for the last newline, so a long transcript costs a few reads. */
    @Override
    public long end(Path transcript) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(transcript)) {
            ByteBuffer window = ByteBuffer.allocate(WINDOW);
            for (long upTo = channel.size(); upTo > 0; ) {
                long from = Math.max(0, upTo - WINDOW);
                window.clear().limit((int) (upTo - from));
                channel.position(from);
                while (window.hasRemaining() && channel.read(window) != -1) {
                    // fill the window
                }
                for (int i = window.position() - 1; i >= 0; i--) {
                    if (window.get(i) == '\n') {
                        return from + i + 1;
                    }
                }
                upTo = from;
            }
            return 0;
        }
    }

    private Format format(Role client) {
        Format format = formats.get(client);
        if (format == null) {
            throw new IllegalArgumentException("no transcript format for " + client.id());
        }
        return format;
    }

    /** A complete line and where it lies: {@code end} is just past its newline. */
    private record Line(String text, long position, long end) {
    }

    /** The complete lines of a file from an offset; a final line without its newline is never returned. */
    private static final class Lines implements AutoCloseable {

        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long position;

        Lines(Path file, long offset) throws IOException {
            SeekableByteChannel channel = Files.newByteChannel(file);
            channel.position(offset);
            this.in = new BufferedInputStream(Channels.newInputStream(channel));
            this.position = offset;
        }

        @Nullable Line next() throws IOException {
            buffer.reset();
            for (int b = in.read(); b != -1; b = in.read()) {
                if (b == '\n') {
                    long start = position;
                    position += buffer.size() + 1;
                    return new Line(buffer.toString(UTF_8), start, position);
                }
                buffer.write(b);
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
