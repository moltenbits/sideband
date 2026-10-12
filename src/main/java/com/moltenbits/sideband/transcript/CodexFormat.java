package com.moltenbits.sideband.transcript;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import jakarta.inject.Singleton;

import java.io.IOException;

/**
 * Codex's rollout file (0.162.1, measured 2026-10-12). A turn starts with a
 * {@code task_started} event naming the {@code turn_id} its hooks receive. What the model
 * said is a {@code response_item} message from the assistant, labelled {@code commentary}
 * when it goes on working and {@code final_answer} when the turn ends; the
 * {@code event_msg} copies of the same words are not read, so nothing is read twice. Each
 * message item has an {@code id}.
 */
@Singleton
class CodexFormat implements Format {

    private static final String FINAL_ANSWER = "final_answer";

    private final ObjectMapper json;

    CodexFormat(ObjectMapper json) {
        this.json = json;
    }

    @Serdeable
    record Line(@Nullable String type, @Nullable Payload payload) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Payload(@Nullable String type, @Nullable String id, @Nullable String role, @Nullable String phase,
                   @Nullable String turnId, @Nullable JsonNode content) {
    }

    @Override
    public Role client() {
        return Role.CODEX;
    }

    @Override
    public Reading read(String text) {
        Line line;
        try {
            line = json.readValue(text, Line.class);
        } catch (IOException | RuntimeException e) {
            return Reading.NOTHING;
        }
        Payload payload = line == null ? null : line.payload();
        if (payload == null) {
            return Reading.NOTHING;
        }
        if ("event_msg".equals(line.type()) && "task_started".equals(payload.type())) {
            return payload.turnId() == null ? Reading.NOTHING : Reading.turnStarted(payload.turnId());
        }
        if (!"response_item".equals(line.type()) || !"message".equals(payload.type()) || !"assistant".equals(payload.role())) {
            return Reading.NOTHING;
        }
        String said = Format.text(payload.content(), "output_text");
        return said == null ? Reading.NOTHING : new Reading(null, said, FINAL_ANSWER.equals(payload.phase()), payload.id());
    }
}
