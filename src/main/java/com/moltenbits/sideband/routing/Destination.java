package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Route;

import java.util.List;

/** The recipients and route a directive selected, and whether a directive was present. */
public record Destination(List<ParticipantId> to, Route route, boolean directed) {

    public Destination {
        to = List.copyOf(to);
    }
}
