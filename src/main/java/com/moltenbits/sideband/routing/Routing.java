package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.ParticipantId;

import java.util.Set;
import java.util.function.Predicate;

/** Resolves where a human's message goes from a directive at the start of its body. */
public interface Routing {

    /**
     * Reads the first non-whitespace token of {@code body}, case-insensitively, in the project
     * of {@code via}, the instance it was typed into. {@code @<name>} selects that name in the
     * project, joined or not, {@code <project>/@<name>} that name in another registered
     * project, and {@code @all} every instance in {@code joined}; anything else routes to
     * {@code via} alone (REQUIREMENTS.md 8.1). A directive to a project {@code registered}
     * does not know routes to {@code via} too, and says so in {@link Destination#unrouted()}.
     * The body is never altered.
     *
     * @param joined     the instances of the project that have a session record, for {@code @all}
     * @param registered whether a project name is registered
     * @throws IllegalArgumentException for {@code @all} when nobody in the project has joined
     */
    Destination resolve(String body, ParticipantId via, Set<ParticipantId> joined, Predicate<String> registered);
}
