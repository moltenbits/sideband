package com.moltenbits.sideband.push;

import com.moltenbits.sideband.handoff.Batch;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Cursor;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Session;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
class HostPushes implements Pushes {

    private final RecipientState recipients;
    private final Handoffs handoffs;
    private final Map<Role, HostPusher> pushers;

    HostPushes(RecipientState recipients, Handoffs handoffs, List<HostPusher> pushers) {
        this.recipients = recipients;
        this.handoffs = handoffs;
        this.pushers = pushers.stream().collect(Collectors.toMap(HostPusher::role, Function.identity()));
    }

    @Override
    public List<PushResult> deliver(Path stateDirectory, Entry entry) {
        List<PushResult> results = new ArrayList<>();
        for (ParticipantId recipient : entry.metadata().to()) {
            recipient.role().ifPresent(role -> {
                Cursor cursor = recipients.load(stateDirectory, role);
                // An entry the recipient already resolved, such as a human's own turn, is never pushed.
                if (recipients.isOpen(cursor, entry)) {
                    results.add(deliver(stateDirectory, entry, role, cursor));
                }
            });
        }
        return results;
    }

    private PushResult deliver(Path stateDirectory, Entry entry, Role role, Cursor cursor) {
        HostPusher pusher = pushers.get(role);
        if (pusher == null) {
            return new PushResult(role, PushOutcome.LISTENER_DELIVERS, null);
        }
        Session session = cursor.session();
        if (session == null) {
            return new PushResult(role, PushOutcome.NO_SESSION, null);
        }
        if (!session.isLive()) {
            return new PushResult(role, PushOutcome.SESSION_DEAD, "session " + session.id() + " process " + session.parentPid() + " is gone");
        }
        Path journalFile = stateDirectory.resolve(Journal.FILE_NAME);
        Batch batch = new Batch(entry.start(), entry.end(), handoffs.prepare(journalFile, List.of(entry)), List.of(), false);
        PushResult result = pusher.push(session, handoffs.envelope(batch));
        if (result.outcome() == PushOutcome.PUSHED) {
            recipients.markDelivered(stateDirectory, role, List.of(entry.metadata().id()));
        }
        return result;
    }
}
