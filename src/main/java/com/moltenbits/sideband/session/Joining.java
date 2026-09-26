package com.moltenbits.sideband.session;

import io.micronaut.core.annotation.Nullable;

/**
 * What a join recorded: the instance's new session record, and the session its earlier
 * record named when the join took the identifier over from another session, so a forgotten
 * client losing it is visible (REQUIREMENTS.md 9.5a).
 */
public record Joining(Session session, @Nullable String replaced) {
}
