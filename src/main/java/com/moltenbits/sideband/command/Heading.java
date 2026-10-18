package com.moltenbits.sideband.command;

import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.core.annotation.Nullable;

import java.util.stream.Collectors;

/**
 * The Markdown heading {@code sideband log} prints for an entry, naming each participant as
 * the reader's project sees it: presentation only, never authoritative.
 */
final class Heading {

    static final String PREFIX = "## ";

    private Heading() {
    }

    /** @param project the reader's project, or null to name every agent with its project */
    static String of(EntryMetadata metadata, @Nullable String project) {
        String recipients = metadata.to().stream()
                .map(participant -> label(participant, project))
                .collect(Collectors.joining(" + "));
        // A via that names the author, as a captured turn's does, adds nothing the heading does not already say.
        String via = metadata.via() == null || metadata.via().equals(metadata.from()) ? "" : " (via " + label(metadata.via(), project) + ")";
        return PREFIX + sanitize(label(metadata.from(), project)) + " → " + sanitize(recipients) + via;
    }

    private static String label(ParticipantId participant, @Nullable String project) {
        return project != null ? participant.label(project) : participant.isHuman() ? participant.label("") : participant.value();
    }

    /** Headings are single lines; control characters would break them. */
    private static String sanitize(String text) {
        return text.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
