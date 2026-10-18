package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code held_prompts} table: the prompt a session typed before holding an instance, one per project, role, and session, and the turn it started. */
@MappedEntity("held_prompts")
record HeldPromptRow(
        @Id @GeneratedValue @Nullable Long id,
        String project,
        String role,
        String sessionId,
        String prompt,
        @Nullable String turnId) {
}
