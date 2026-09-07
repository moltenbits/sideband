package com.moltenbits.sideband.store;

import com.moltenbits.sideband.protocol.EntryMetadata;
import io.micronaut.serde.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads the Markdown journal the store replaced, so an existing discussion carries over.
 * <p>
 * On disk an entry was:
 * <pre>
 * &lt;!-- sideband:v1
 * {metadata json, including body_bytes}
 * --&gt;
 *
 * ## Heading
 *
 * body bytes (exactly body_bytes long)
 * &lt;!-- /sideband --&gt;
 * </pre>
 * A crashed writer could leave a fragment, closed by the next writer with an abort marker.
 * This is a reader only: nothing writes that format any more.
 */
final class LegacyJournal {

    /** The journal file name inside the state directory. */
    static final String FILE_NAME = "journal.md";

    private static final byte[] OPENER_PREFIX = "<!-- sideband:".getBytes(UTF_8);
    private static final String VERSION = "v1";
    private static final byte[] COMMENT_CLOSE_LINE = "-->\n".getBytes(UTF_8);
    private static final byte[] TERMINATOR_LINE = "<!-- /sideband -->\n".getBytes(UTF_8);
    private static final byte[] ABORT_LINE = "<!-- sideband:aborted -->\n".getBytes(UTF_8);
    private static final byte[] HEADING_PREFIX = "## ".getBytes(UTF_8);
    private static final byte NEWLINE = '\n';

    /**
     * One entry and the byte range it occupied, which the session records refer to.
     *
     * @param end the offset just past the closing marker: a session's offset or watermark at or past it covers this entry
     */
    record LegacyEntry(EntryMetadata metadata, String body, long start, long end) {
    }

    /** What the read produced: the entries in physical order and, per skipped region, its offset and reason. */
    record Parsed(List<LegacyEntry> entries, List<String> problems) {
    }

    private final ObjectMapper json;

    LegacyJournal(ObjectMapper json) {
        this.json = json;
    }

    Parsed parse(byte[] bytes) {
        List<LegacyEntry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int pos = 0;
        while (true) {
            int opener = nextMarkerAtLineStart(bytes, pos);
            if (opener < 0) {
                if (!isBlank(bytes, pos, bytes.length)) {
                    problems.add("unframed bytes at offset " + pos);
                }
                break;
            }
            if (!isBlank(bytes, pos, opener)) {
                problems.add("unframed bytes at offset " + pos);
            }
            Outcome outcome = parseAt(bytes, opener);
            switch (outcome) {
                case Outcome.Complete complete -> {
                    entries.add(complete.entry());
                    pos = complete.next();
                }
                case Outcome.Malformed malformed -> {
                    problems.add(malformed.reason() + " at offset " + opener);
                    pos = malformed.next();
                }
                case Outcome.Incomplete incomplete -> {
                    int abort = indexOfLine(bytes, opener + OPENER_PREFIX.length, ABORT_LINE);
                    if (abort < 0) {
                        problems.add("incomplete entry at offset " + opener);
                        return new Parsed(entries, problems);
                    }
                    problems.add("aborted entry at offset " + opener);
                    pos = abort + ABORT_LINE.length;
                }
                case Outcome.Boundary boundary -> pos = boundary.next();
            }
        }
        return new Parsed(entries, problems);
    }

    private sealed interface Outcome {
        record Complete(LegacyEntry entry, int next) implements Outcome { }
        record Malformed(String reason, int next) implements Outcome { }
        record Incomplete() implements Outcome { }
        record Boundary(int next) implements Outcome { }
    }

    private Outcome parseAt(byte[] bytes, int opener) {
        int p = opener + OPENER_PREFIX.length;
        int lineEnd = indexOf(bytes, NEWLINE, p);
        if (lineEnd < 0) {
            return new Outcome.Incomplete();
        }
        String token = new String(bytes, p, lineEnd - p, UTF_8);
        if (token.equals("aborted -->")) {
            return new Outcome.Boundary(lineEnd + 1);
        }
        if (!token.equals(VERSION)) {
            return malformed(bytes, opener, "unsupported protocol version '" + token + "'");
        }
        p = lineEnd + 1;

        int jsonEnd = indexOf(bytes, NEWLINE, p);
        if (jsonEnd < 0) {
            return new Outcome.Incomplete();
        }
        EntryMetadata metadata;
        long bodyBytes;
        try {
            String line = new String(bytes, p, jsonEnd - p, UTF_8);
            Map<?, ?> fields = json.readValue(line, Map.class);
            bodyBytes = ((Number) fields.get("body_bytes")).longValue();
            metadata = json.readValue(line, EntryMetadata.class);
        } catch (IOException | RuntimeException e) {
            return malformed(bytes, opener, "invalid metadata: " + rootMessage(e));
        }
        p = jsonEnd + 1;

        if (!startsWith(bytes, p, COMMENT_CLOSE_LINE)) {
            return p + COMMENT_CLOSE_LINE.length > bytes.length
                    ? new Outcome.Incomplete()
                    : malformed(bytes, opener, "missing comment close after metadata");
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

        long needed = (long) p + bodyBytes + 1 + TERMINATOR_LINE.length;
        if (needed > bytes.length) {
            return new Outcome.Incomplete();
        }
        int bodyStart = p;
        int bodyEnd = p + (int) bodyBytes;
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
        return new Outcome.Complete(new LegacyEntry(metadata, body, opener, p), p);
    }

    /** Skips a malformed entry up to the next marker at a line start, so one problem yields one report. */
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

    private static int nextMarkerAtLineStart(byte[] bytes, int from) {
        return indexOfLine(bytes, from, OPENER_PREFIX);
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
