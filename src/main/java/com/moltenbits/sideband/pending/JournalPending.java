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
import com.moltenbits.sideband.session.Deliveries;
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
    private final Deliveries deliveries;
    private final Clock clock;

    JournalPending(Journal journal, Sessions sessions, Handoffs handoffs, Deliveries deliveries, Clock clock) {
        this.journal = journal;
        this.sessions = sessions;
        this.handoffs = handoffs;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @Override
    public PendingReport report(Path stateDirectory, ParticipantId self) {
        Read all = journal.readAfter(stateDirectory, 0);
        Optional<Session> session = sessions.load(stateDirectory, self);
        long watermark = session.map(Session::watermark).orElse(Long.MAX_VALUE);
        boolean resumed = session.map(Session::resumed).orElse(false);
        long offset = session.map(Session::offset).orElse(0L);
        Map<String, List<EntryMetadata>> responses = responses(all.entries());
        OffsetDateTime now = OffsetDateTime.now(clock);

        List<Entry> open = new ArrayList<>();
        List<Entry> inProgress = new ArrayList<>();
        Map<String, OffsetDateTime> acknowledged = new HashMap<>();
        List<Entry> updates = new ArrayList<>();
        List<OutgoingReport> outgoing = new ArrayList<>();
        for (Entry entry : all.entries()) {
            EntryMetadata m = entry.metadata();
            if (Addressing.concerns(m, self) && m.type() != MessageType.ACK) {
                if (m.expectsReply()) {
                    List<EntryMetadata> mine = responses.getOrDefault(m.id(), List.of()).stream()
                            .filter(r -> r.from().equals(self)).toList();
                    if (mine.stream().anyMatch(r -> answers(r, m)) || superseded(entry, self, all.entries())) {
                        continue;
                    }
                    Optional<OffsetDateTime> acked = latest(mine, MessageType.ACK);
                    acked.ifPresent(at -> acknowledged.put(m.id(), at));
                    (acked.isPresent() ? inProgress : open).add(entry);
                } else if (entry.seq() > offset) {
                    updates.add(entry);
                }
            }
            if (Addressing.isOutgoingRequest(m, self)) {
                List<EntryMetadata> theirs = responses.getOrDefault(m.id(), List.of()).stream()
                        .filter(r -> !r.from().equals(self)).toList();
                if (theirs.stream().anyMatch(r -> answers(r, m)) || supersededForAnyRecipient(entry, all.entries())) {
                    continue;
                }
                Optional<OffsetDateTime> acked = latest(theirs, MessageType.ACK);
                long silence = Math.max(0, Duration.between(acked.orElse(m.createdAt()), now).getSeconds());
                outgoing.add(new OutgoingReport(m.id(), m.to(), m.createdAt(), acked.orElse(null),
                        theirs.stream().filter(r -> r.type() == MessageType.ACK).map(EntryMetadata::id).toList(),
                        silence));
            }
        }
        // After --resume the operator wants what was waiting taken up: a lone request is acted
        // on without asking, several are confirmed first. After a plain join, everything that
        // predates the session is confirmed.
        boolean confirmOld = !resumed || open.size() + inProgress.size() > 1;
        // What the writer already pushed into this very session arrives by its other path too,
        // and the report says so on each such entry; a session that replaced the one pushed
        // into has nothing in flight and is shown everything plainly.
        Map<Long, OffsetDateTime> pushed = session.map(s -> deliveries.pushedInto(stateDirectory, self, s.id())).orElse(Map.of());
        return new PendingReport(
                Handling.forRole(self.role().orElseThrow()),
                session.orElse(null),
                items(stateDirectory, open, confirmOld ? watermark : 0L, acknowledged, pushed),
                items(stateDirectory, inProgress, confirmOld ? watermark : 0L, acknowledged, pushed),
                prepare(stateDirectory, updates, pushed),
                outgoing,
                all.end());
    }

    private List<OpenItem> items(Path stateDirectory, List<Entry> entries, long watermark,
                                 Map<String, OffsetDateTime> acknowledged, Map<Long, OffsetDateTime> pushed) {
        List<Handoff> prepared = prepare(stateDirectory, entries, pushed);
        List<OpenItem> items = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            items.add(new OpenItem(prepared.get(i), entry.seq() <= watermark, acknowledged.get(entry.metadata().id())));
        }
        return items;
    }

    private List<Handoff> prepare(Path stateDirectory, List<Entry> entries, Map<Long, OffsetDateTime> pushed) {
        return handoffs.prepare(stateDirectory, entries).stream()
                .map(handoff -> handoff.withPushedAt(pushed.get(handoff.seq())))
                .toList();
    }

    /**
     * A reply closes a request only if it expects nothing back (a reply that asks is a
     * question) and is addressed to whoever asked: a reply sent to the operator alone about
     * an agent's request leaves that agent's request open, since the agent never sees it.
     */
    static boolean answers(EntryMetadata response, EntryMetadata request) {
        return response.type() == MessageType.REPLY && !response.expectsReply() && response.addresses(request.from());
    }

    /**
     * An agent's request to a client is superseded by that agent's next actionable request
     * to the same client: an agent delegates one thing at a time, so a later request is the
     * current one and the earlier is dismissed, listed nowhere and awaited by nobody,
     * however it was left. A request that expects nothing back is context and replaces no
     * work. A human's prompts are never superseded; the operator may stack instructions.
     */
    static boolean superseded(Entry request, ParticipantId recipient, List<Entry> entries) {
        EntryMetadata m = request.metadata();
        if (!m.isAgentAuthored() || m.type() != MessageType.REQUEST) {
            return false;
        }
        return entries.stream().anyMatch(e -> e.seq() > request.seq()
                && e.metadata().type() == MessageType.REQUEST && e.metadata().expectsReply()
                && e.metadata().from().equals(m.from())
                && e.metadata().addresses(recipient));
    }

    /** Superseded from the sender's side: a later request of theirs to any client this one addressed. */
    static boolean supersededForAnyRecipient(Entry request, List<Entry> entries) {
        return request.metadata().to().stream().filter(recipient -> !recipient.isHuman())
                .anyMatch(recipient -> superseded(request, recipient, entries));
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
