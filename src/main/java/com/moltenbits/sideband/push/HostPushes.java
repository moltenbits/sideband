package com.moltenbits.sideband.push;

import com.moltenbits.sideband.handoff.Batch;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.pending.Addressing;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
class HostPushes implements Pushes {

    private final Handoffs handoffs;
    private final Map<Role, HostPusher> pushers;

    HostPushes(Handoffs handoffs, List<HostPusher> pushers) {
        this.handoffs = handoffs;
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
        HostPusher pusher = pushers.get(role);
        if (pusher == null) {
            return new PushResult(role, PushOutcome.LISTENER_DELIVERS, null);
        }
        Path journalFile = stateDirectory.resolve(Journal.FILE_NAME);
        Batch batch = Batch.forRole(role, entry.start(), entry.end(), handoffs.prepare(journalFile, List.of(entry)), List.of(), false);
        return pusher.push(stateDirectory, handoffs.envelope(batch));
    }
}
