package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.handoff.Handling;
import com.moltenbits.sideband.handoff.Handoff;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Singleton
class JournalPending implements Pending {

    private final Journal journal;
    private final Sessions sessions;
    private final Handoffs handoffs;
    private final Clock clock;

    JournalPending(Journal journal, Sessions sessions, Handoffs handoffs, Clock clock) {
        this.journal = journal;
        this.sessions = sessions;
        this.handoffs = handoffs;
        this.clock = clock;
    }

    @Override
    public PendingReport report(Path stateDirectory, Role role) {
        Path file = stateDirectory.resolve(Journal.FILE_NAME);
        Read all = journal.readCompleteFrom(file, 0);
        Optional<Session> session = sessions.load(stateDirectory, role);
        long watermark = session.map(Session::watermark).orElse(Long.MAX_VALUE);
        boolean resumed = session.map(Session::resumed).orElse(false);
        long offset = session.map(Session::offset).orElse(0L);
        ParticipantId self = ParticipantId.of(role);
        Map<String, List<EntryMetadata>> responses = responses(all.entries());
        OffsetDateTime now = OffsetDateTime.now(clock);

        List<Entry> open = new ArrayList<>();
        List<Entry> inProgress = new ArrayList<>();
        Map<String, OffsetDateTime> acknowledged = new HashMap<>();
        List<Entry> updates = new ArrayList<>();
        List<OutgoingReport> outgoing = new ArrayList<>();
        for (Entry entry : all.entries()) {
            EntryMetadata m = entry.metadata();
            if (Addressing.concerns(m, role) && m.type() != MessageType.ACK) {
                if (m.expectsReply()) {
                    List<EntryMetadata> mine = responses.getOrDefault(m.id(), List.of()).stream()
                            .filter(r -> r.from().equals(self)).toList();
                    if (mine.stream().anyMatch(r -> r.type() == MessageType.REPLY)) {
                        continue;
                    }
                    Optional<OffsetDateTime> acked = latest(mine, MessageType.ACK);
                    acked.ifPresent(at -> acknowledged.put(m.id(), at));
                    (acked.isPresent() ? inProgress : open).add(entry);
                } else if (entry.end() > offset) {
                    updates.add(entry);
                }
            }
            if (Addressing.isOutgoingRequest(m, role)) {
                List<EntryMetadata> theirs = responses.getOrDefault(m.id(), List.of()).stream()
                        .filter(r -> !r.from().equals(self)).toList();
                if (theirs.stream().anyMatch(r -> r.type() == MessageType.REPLY)) {
                    continue;
                }
                Optional<OffsetDateTime> acked = latest(theirs, MessageType.ACK);
                long silence = Math.max(0, Duration.between(acked.orElse(m.createdAt()), now).getSeconds());
                boolean overdue = m.heartbeatSeconds() != null && silence > m.heartbeatSeconds();
                outgoing.add(new OutgoingReport(m.id(), m.to(), m.createdAt(), m.heartbeatSeconds(), acked.orElse(null),
                        theirs.stream().filter(r -> r.type() == MessageType.ACK).map(EntryMetadata::id).toList(),
                        silence, overdue));
            }
        }
        // After --resume the operator wants what was waiting taken up: a lone request is acted
        // on without asking, several are confirmed first. After a plain join, everything that
        // predates the session is confirmed.
        boolean confirmOld = !resumed || open.size() + inProgress.size() > 1;
        return new PendingReport(
                Handling.forRole(role),
                session.orElse(null),
                items(file, open, confirmOld ? watermark : 0L, acknowledged),
                items(file, inProgress, confirmOld ? watermark : 0L, acknowledged),
                handoffs.prepare(file, updates),
                outgoing,
                all.diagnostics(),
                all.end());
    }

    private List<OpenItem> items(Path file, List<Entry> entries, long watermark, Map<String, OffsetDateTime> acknowledged) {
        List<Handoff> prepared = handoffs.prepare(file, entries);
        List<OpenItem> items = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            items.add(new OpenItem(prepared.get(i), entry.end() <= watermark, acknowledged.get(entry.metadata().id())));
        }
        return items;
    }

    private static Optional<OffsetDateTime> latest(List<EntryMetadata> responses, MessageType type) {
        return responses.stream().filter(r -> r.type() == type).map(EntryMetadata::createdAt).max(Comparator.naturalOrder());
    }

    /**
     * Every entry that answers a request, keyed by the request's id. An entry answers the
     * nearest actionable entry reachable through its {@code reply_to} chain that someone
     * else wrote, so a reply to a clarification still counts against the original request.
     */
    static Map<String, List<EntryMetadata>> responses(List<Entry> entries) {
        Map<String, EntryMetadata> byId = new HashMap<>();
        for (Entry entry : entries) {
            byId.put(entry.metadata().id(), entry.metadata());
        }
        Map<String, List<EntryMetadata>> responses = new HashMap<>();
        for (Entry entry : entries) {
            EntryMetadata m = entry.metadata();
            Set<String> visited = new HashSet<>();
            String current = m.replyTo();
            while (current != null && visited.add(current)) {
                EntryMetadata target = byId.get(current);
                if (target == null) {
                    break;
                }
                if (target.expectsReply() && !target.from().equals(m.from())) {
                    responses.computeIfAbsent(target.id(), k -> new ArrayList<>()).add(m);
                    break;
                }
                current = target.replyTo();
            }
        }
        return responses;
    }
}
