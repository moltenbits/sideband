package com.moltenbits.sideband.transcript;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.tree.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** How one host's transcript records turns and messages. */
interface Format {

    /** The client whose transcript this reads. */
    Role client();

    /** What a record means to the reader; null fields are absent. */
    record Reading(@Nullable String turnStarted, @Nullable String said, boolean endsTurn, @Nullable String id) {

        static final Reading NOTHING = new Reading(null, null, false, null);

        static Reading turnStarted(String turn) {
            return new Reading(turn, null, false, null);
        }
    }

    /** Reads one complete line; anything unrecognized, unparseable included, is {@link Reading#NOTHING}. */
    Reading read(String line);

    /** Whether {@code line} may start a turn: a cheap test before parsing, so a scan of a whole transcript parses few lines. */
    boolean mayStartTurn(String line);

    /** The text of a message's content blocks of {@code blockType}, joined; null when none has words in it. */
    static @Nullable String text(@Nullable JsonNode content, String blockType) {
        if (content == null || !content.isArray()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode block : content.values()) {
            JsonNode type = block.get("type");
            JsonNode text = block.get("text");
            if (type != null && type.isString() && blockType.equals(type.getStringValue())
                    && text != null && text.isString() && !text.getStringValue().isBlank()) {
                texts.add(text.getStringValue());
            }
        }
        return texts.isEmpty() ? null : String.join("\n\n", texts);
    }
}
