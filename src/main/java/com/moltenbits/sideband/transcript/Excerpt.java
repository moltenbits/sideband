package com.moltenbits.sideband.transcript;

import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * What one read found.
 *
 * @param messages what the agent said, in transcript order
 * @param end      the offset just past the last complete line read, whatever it held
 * @param turn     the turn the reader is in at {@code end}
 */
public record Excerpt(List<Message> messages, long end, @Nullable String turn) {

    public Excerpt {
        messages = List.copyOf(messages);
    }
}
