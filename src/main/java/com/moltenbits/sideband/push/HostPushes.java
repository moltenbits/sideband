package com.moltenbits.sideband.push;

import com.moltenbits.sideband.handoff.Batch;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.pending.Addressing;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Deliveries;
import com.moltenbits.sideband.session.Sessions;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pushes an entry to each client recipient through that host's own pusher, and records a
 * push the host accepted against the session holding the role, so that a {@code pending}
 * read in that session can say the entry is also on its way in. A refused or failed push
 * records nothing: the entry then reaches the role through {@code pending} alone.
 */
@Singleton
class HostPushes implements Pushes {

    private final Handoffs handoffs;
    private final Sessions sessions;
    private final Deliveries deliveries;
    private final Map<Role, HostPusher> pushers;

    HostPushes(Handoffs handoffs, Sessions sessions, Deliveries deliveries, List<HostPusher> pushers) {
        this.handoffs = handoffs;
        this.sessions = sessions;
        this.deliveries = deliveries;
        this.pushers = pushers.stream().collect(Collectors.toMap(HostPusher::role, Function.identity()));
    }

    @Override
    public List<PushResult> deliver(Path stateDirectory, Entry entry) {
        List<PushResult> results = new ArrayList<>();
        if (entry.metadata().type() == MessageType.ACK) {
            // An ack is for the requester's next look at its outgoing requests, never a wake.
            return results;
        }
        for (ParticipantId recipient : entry.metadata().to()) {
            recipient.role().ifPresent(role -> {
                if (Addressing.concerns(entry.metadata(), role)) {
                    results.add(deliver(stateDirectory, entry, role));
                }
            });
        }
        return results;
    }

    private PushResult deliver(Path stateDirectory, Entry entry, Role role) {
        Batch batch = Batch.forRole(role, entry.seq(), entry.seq(), handoffs.prepare(stateDirectory, List.of(entry)), false);
        PushResult result = pushers.get(role).push(stateDirectory, entry.metadata().from(), handoffs.envelope(batch));
        if (result.outcome() == PushOutcome.PUSHED) {
            sessions.load(stateDirectory, role).ifPresent(session -> deliveries.record(stateDirectory, entry.seq(), role, session.id()));
        }
        return result;
    }
}
