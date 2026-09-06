package com.moltenbits.sideband.journal;

import com.moltenbits.sideband.protocol.EntryMetadata;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Encodes entries to bytes and parses them back with a byte-oriented state machine.
 * <p>
 * On disk an entry is:
 * <pre>
 * &lt;!-- sideband:v1
 * {metadata json}
 * --&gt;
 *
 * ## Heading
 *
 * body bytes (exactly body_bytes long)
 * &lt;!-- /sideband --&gt;
 * </pre>
 * The newline between the body and the closing marker is a separator outside the counted body.
 * The journal writes a blank line after each entry; it is not part of the entry.
 */
@Singleton
final class EntryCodec {

    static final byte[] OPENER_PREFIX = "<!-- sideband:".getBytes(UTF_8);
    static final byte[] OPENER_LINE = ("<!-- sideband:" + Journal.PROTOCOL_VERSION + "\n").getBytes(UTF_8);
    static final byte[] COMMENT_CLOSE_LINE = "-->\n".getBytes(UTF_8);
    static final byte[] TERMINATOR_LINE = "<!-- /sideband -->\n".getBytes(UTF_8);
    /** Appended by a writer to close a fragment a crashed writer left behind. */
    static final byte[] ABORT_LINE = "<!-- sideband:aborted -->\n".getBytes(UTF_8);
    /** Written after each entry so the raw file stays readable; never part of an entry. */
    static final byte[] SEPARATOR = "\n".getBytes(UTF_8);
    private static final byte NEWLINE = '\n';
    private static final byte[] HEADING_PREFIX = Heading.PREFIX.getBytes(UTF_8);

    private final ObjectMapper json;

    EntryCodec(ObjectMapper json) {
        this.json = json;
    }

    static int bodyLength(String body) {
        return body.getBytes(UTF_8).length;
    }

    byte[] encode(EntryMetadata metadata, String body) {
        byte[] bodyBytes = body.getBytes(UTF_8);
        if (bodyBytes.length != metadata.bodyBytes()) {
            throw new IllegalArgumentException("metadata body_bytes " + metadata.bodyBytes()
                    + " does not match the body's " + bodyBytes.length + " bytes");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes.length + 512);
            out.write(OPENER_LINE);
            out.write(json.writeValueAsString(metadata).getBytes(UTF_8));
            out.write(NEWLINE);
            out.write(COMMENT_CLOSE_LINE);
            out.write(NEWLINE);
            out.write(Heading.of(metadata).getBytes(UTF_8));
            out.write(NEWLINE);
            out.write(NEWLINE);
            out.write(bodyBytes);
            out.write(NEWLINE);
            out.write(TERMINATOR_LINE);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses {@code bytes}, which begin at absolute journal offset {@code base}. */
    Read parse(byte[] bytes, long base) {
        List<Entry> entries = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        int pos = 0;
        int end;
        while (true) {
            int opener = nextMarkerAtLineStart(bytes, pos);
            if (opener < 0) {
                end = trailing(bytes, pos, base, diagnostics);
                break;
            }
            if (!isBlank(bytes, pos, opener)) {
                diagnostics.add(new Diagnostic(base + pos, "unframed bytes before the next entry"));
            }
            Outcome outcome = parseAt(bytes, opener, base);
            switch (outcome) {
                case Outcome.Complete complete -> {
                    entries.add(complete.entry());
                    pos = complete.next();
                }
                case Outcome.Malformed malformed -> {
                    diagnostics.add(new Diagnostic(base + opener, malformed.reason()));
                    pos = malformed.next();
                }
                case Outcome.Incomplete incomplete -> {
                    int abort = indexOfLine(bytes, opener + OPENER_PREFIX.length, ABORT_LINE);
                    if (abort < 0) {
                        end = opener;
                        return new Read(base, base + end, entries, diagnostics);
                    }
                    diagnostics.add(new Diagnostic(base + opener, "incomplete entry closed by an abort marker"));
                    pos = abort + ABORT_LINE.length;
                }
                case Outcome.Boundary boundary -> pos = boundary.next();
            }
        }
        return new Read(base, base + end, entries, diagnostics);
    }

    /** Bytes after the last marker: blank, an unfinished opener still being written, or junk. */
    private static int trailing(byte[] bytes, int pos, long base, List<Diagnostic> diagnostics) {
        if (isBlank(bytes, pos, bytes.length)) {
            return bytes.length;
        }
        int lastLine = lastLineStart(bytes, pos);
        int remaining = bytes.length - lastLine;
        if (remaining < OPENER_PREFIX.length
                && Arrays.equals(bytes, lastLine, bytes.length, OPENER_PREFIX, 0, remaining)
                && isBlank(bytes, pos, lastLine)) {
            return lastLine;
        }
        diagnostics.add(new Diagnostic(base + pos, "unframed bytes at the end of the journal"));
        return bytes.length;
    }

    private sealed interface Outcome {
        record Complete(Entry entry, int next) implements Outcome { }
        record Malformed(String reason, int next) implements Outcome { }
        record Incomplete() implements Outcome { }
        /** An abort marker met while scanning: not an entry, nothing to report. */
        record Boundary(int next) implements Outcome { }
    }

    private Outcome parseAt(byte[] bytes, int opener, long base) {
        int p = opener + OPENER_PREFIX.length;
        int lineEnd = indexOf(bytes, NEWLINE, p);
        if (lineEnd < 0) {
            return new Outcome.Incomplete();
        }
        String token = new String(bytes, p, lineEnd - p, UTF_8);
        if (token.equals("aborted -->")) {
            return new Outcome.Boundary(lineEnd + 1);
        }
        if (!token.equals(Journal.PROTOCOL_VERSION)) {
            return malformed(bytes, opener, "unsupported protocol version '" + token + "'");
        }
        p = lineEnd + 1;

        int jsonEnd = indexOf(bytes, NEWLINE, p);
        if (jsonEnd < 0) {
            return new Outcome.Incomplete();
        }
        EntryMetadata metadata;
        try {
            metadata = json.readValue(new String(bytes, p, jsonEnd - p, UTF_8), EntryMetadata.class);
        } catch (IOException | RuntimeException e) {
            return malformed(bytes, opener, "invalid metadata: " + rootMessage(e));
        }
        p = jsonEnd + 1;

        Outcome expected = expect(bytes, p, COMMENT_CLOSE_LINE, opener, "missing comment close after metadata");
        if (expected != null) {
            return expected;
        }
        p += COMMENT_CLOSE_LINE.length;
        if (p >= bytes.length) {
            return new Outcome.Incomplete();
        }
        if (bytes[p] != NEWLINE) {
            return malformed(bytes, opener, "missing blank line before heading");
        }
        p++;

        int headingEnd = indexOf(bytes, NEWLINE, p);
        if (headingEnd < 0) {
            return new Outcome.Incomplete();
        }
        if (!startsWith(bytes, p, HEADING_PREFIX)) {
            return malformed(bytes, opener, "missing heading");
        }
        p = headingEnd + 1;
        if (p >= bytes.length) {
            return new Outcome.Incomplete();
        }
        if (bytes[p] != NEWLINE) {
            return malformed(bytes, opener, "missing blank line before body");
        }
        p++;

        long needed = (long) p + metadata.bodyBytes() + 1 + TERMINATOR_LINE.length;
        if (needed > bytes.length) {
            return new Outcome.Incomplete();
        }
        int bodyStart = p;
        int bodyEnd = p + (int) metadata.bodyBytes();
        String body;
        try {
            body = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, bodyStart, bodyEnd - bodyStart))
                    .toString();
        } catch (CharacterCodingException e) {
            return malformed(bytes, opener, "body is not valid UTF-8");
        }
        p = bodyEnd;
        if (bytes[p] != NEWLINE) {
            return malformed(bytes, opener, "body_bytes does not match the body");
        }
        p++;
        if (!startsWith(bytes, p, TERMINATOR_LINE)) {
            return malformed(bytes, opener, "closing marker not found where body_bytes ends");
        }
        p += TERMINATOR_LINE.length;
        return new Outcome.Complete(new Entry(metadata, body, base + opener, base + p), p);
    }

    private static Outcome expect(byte[] bytes, int p, byte[] expected, int opener, String reason) {
        if (p + expected.length > bytes.length) {
            return Arrays.equals(bytes, p, bytes.length, expected, 0, bytes.length - p)
                    ? new Outcome.Incomplete()
                    : malformed(bytes, opener, reason);
        }
        return startsWith(bytes, p, expected) ? null : malformed(bytes, opener, reason);
    }

    /** Skips a malformed entry up to the next marker at a line start, so one problem yields one diagnostic. */
    private static Outcome.Malformed malformed(byte[] bytes, int opener, String reason) {
        int next = nextMarkerAtLineStart(bytes, opener + OPENER_PREFIX.length);
        return new Outcome.Malformed(reason, next < 0 ? bytes.length : next);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    /** The next {@code <!-- sideband:} that begins a line, at or after {@code from}. */
    private static int nextMarkerAtLineStart(byte[] bytes, int from) {
        int i = from;
        while (i < bytes.length) {
            if ((i == 0 || bytes[i - 1] == NEWLINE) && startsWith(bytes, i, OPENER_PREFIX)) {
                return i;
            }
            int nl = indexOf(bytes, NEWLINE, i);
            if (nl < 0) {
                return -1;
            }
            i = nl + 1;
        }
        return -1;
    }

    private static int indexOfLine(byte[] bytes, int from, byte[] line) {
        int i = from;
        while (i < bytes.length) {
            if ((i == 0 || bytes[i - 1] == NEWLINE) && startsWith(bytes, i, line)) {
                return i;
            }
            int nl = indexOf(bytes, NEWLINE, i);
            if (nl < 0) {
                return -1;
            }
            i = nl + 1;
        }
        return -1;
    }

    private static int lastLineStart(byte[] bytes, int from) {
        int i = bytes.length - 1;
        while (i >= from && bytes[i] != NEWLINE) {
            i--;
        }
        return i + 1;
    }

    private static boolean isBlank(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != NEWLINE && bytes[i] != ' ' && bytes[i] != '\t' && bytes[i] != '\r') {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, int at, byte[] prefix) {
        return at + prefix.length <= bytes.length
                && Arrays.equals(bytes, at, at + prefix.length, prefix, 0, prefix.length);
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
