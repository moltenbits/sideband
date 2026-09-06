package com.moltenbits.sideband.journal;

import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;

import java.util.stream.Collectors;

/** The generated Markdown heading: presentation only, never authoritative. */
final class Heading {

    static final String PREFIX = "## ";

    private Heading() {
    }

    static String of(EntryMetadata metadata) {
        String recipients = metadata.to().stream()
                .map(ParticipantId::displayName)
                .collect(Collectors.joining(" + "));
        String via = metadata.via() == null ? "" : " (via " + metadata.via().displayName() + ")";
        return PREFIX + sanitize(metadata.from().displayName()) + " → " + sanitize(recipients) + via;
    }

    /** Headings are single lines; control characters would break the framing. */
    private static String sanitize(String text) {
        return text.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
