package com.moltenbits.sideband.store;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

/** One row of the {@code held_prompts} table: the prompt a role's session typed before joining, keyed by the role. */
@MappedEntity("held_prompts")
record HeldPromptRow(
        @Id String role,
        String sessionId,
        String prompt) {
}
