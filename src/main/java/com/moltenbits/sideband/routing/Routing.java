package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.Role;

/** Resolves where a human's message goes from a directive at the start of its body. */
public interface Routing {

    /**
     * Reads the first non-whitespace token of {@code body}. {@code @claude}, {@code @codex},
     * and {@code @all} (case-insensitive) select recipients; anything else routes to
     * {@code via} alone. The body is never altered.
     */
    Resolution resolve(String body, Role via);
}
