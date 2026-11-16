package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Route;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * The recipients and route a directive selected, and whether a directive was present.
 *
 * @param unrouted when the directive named a project nobody registered, that project: the
 *                 message then goes to the instance it was typed into, only so it is recorded
 */
public record Destination(List<ParticipantId> to, Route route, boolean directed, @Nullable String unrouted) {

    public Destination {
        to = List.copyOf(to);
    }

    public Destination(List<ParticipantId> to, Route route, boolean directed) {
        this(to, route, directed, null);
    }
}
