package com.moltenbits.sideband.push;

import com.moltenbits.sideband.handoff.Batch;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.pending.Addressing;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Deliveries;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pushes an entry to each client instance it is for through that host's own pusher, and records a
 * push the host accepted against the session the pusher says it delivered into, so that a
 * {@code pending} read in that session can say the entry is also on its way in. The
 * destination comes from the pusher, which knows where it posted; the instance's session
 * record is not consulted afterwards, since a join or a clear may have moved it while the
 * push ran. A refused or failed push, or one whose destination the pusher cannot name,
 * records nothing: the entry then reaches the instance through {@code pending} alone.
 */
@Singleton
class HostPushes implements Pushes {

    private final Handoffs handoffs;
    private final Deliveries deliveries;
    private final Map<Role, HostPusher> pushers;

    HostPushes(Handoffs handoffs, Deliveries deliveries, List<HostPusher> pushers) {
        this.handoffs = handoffs;
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
            if (!recipient.isHuman() && Addressing.concerns(entry.metadata(), recipient)) {
                results.add(deliver(stateDirectory, entry, recipient));
            }
        }
        return results;
    }

    private PushResult deliver(Path stateDirectory, Entry entry, ParticipantId recipient) {
        Role role = recipient.role().orElseThrow();
        Batch batch = Batch.forRole(role, entry.seq(), entry.seq(), handoffs.prepare(stateDirectory, List.of(entry)), false);
        PushResult result = pushers.get(role).push(stateDirectory, recipient, entry.metadata().from(), handoffs.envelope(batch));
        if (result.outcome() == PushOutcome.PUSHED && result.session() != null) {
            deliveries.record(stateDirectory, entry.seq(), recipient, result.session());
        }
        return result;
    }
}
