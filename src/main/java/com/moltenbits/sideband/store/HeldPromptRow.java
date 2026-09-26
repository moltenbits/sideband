package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code held_prompts} table: the prompt a session typed before holding an instance, one per role and session. */
@MappedEntity("held_prompts")
record HeldPromptRow(
        @Id @GeneratedValue @Nullable Long id,
        String role,
        String sessionId,
        String prompt) {
}
