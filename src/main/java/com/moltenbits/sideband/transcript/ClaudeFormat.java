package com.moltenbits.sideband.transcript;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.Set;

/**
 * Claude Code's session transcript (2.1.296, measured 2026-10-12). Every record of a turn
 * the operator or a host event started, tool results included, carries that turn's
 * {@code promptId}, the {@code prompt_id} its hooks receive; assistant records do not.
 * Each assistant content block is its own record, and the block that ends the turn is the
 * one whose message stopped for any reason but to use a tool. Subagents' records are
 * marked as sidechains, and the host's own notices as a synthetic model. Every record has a
 * {@code uuid}.
 */
@Singleton
class ClaudeFormat implements Format {

    /** Stop reasons that mean the model will go on in the same turn. */
    private static final Set<String> CONTINUING = Set.of("tool_use", "pause_turn");

    private final ObjectMapper json;

    ClaudeFormat(ObjectMapper json) {
        this.json = json;
    }

    @Serdeable
    record Line(@Nullable String type, @Nullable String uuid, @Nullable String promptId, @Nullable Boolean isSidechain,
                @Nullable Boolean isApiErrorMessage, @Nullable Body message) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Body(@Nullable String model, @Nullable String stopReason, @Nullable JsonNode content) {
    }

    @Override
    public Role client() {
        return Role.CLAUDE;
    }

    @Override
    public Reading read(String text) {
        Line line;
        try {
            line = json.readValue(text, Line.class);
        } catch (IOException | RuntimeException e) {
            return Reading.NOTHING;
        }
        if (line == null || Boolean.TRUE.equals(line.isSidechain())) {
            return Reading.NOTHING;
        }
        if ("user".equals(line.type())) {
            return line.promptId() == null ? Reading.NOTHING : Reading.turnStarted(line.promptId());
        }
        Body body = line.message();
        if (!"assistant".equals(line.type()) || body == null || Boolean.TRUE.equals(line.isApiErrorMessage())
                || "<synthetic>".equals(body.model())) {
            return Reading.NOTHING;
        }
        String said = Format.text(body.content(), "text");
        if (said == null) {
            return Reading.NOTHING;
        }
        return new Reading(null, said, body.stopReason() != null && !CONTINUING.contains(body.stopReason()), line.uuid());
    }
}
