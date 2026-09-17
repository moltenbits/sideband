package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rule. A client whose latest word since the operator's last prompt went to the operator
 * alone, a question or a result for the operator's own terminal, wants attention, whichever
 * client the operator typed into. Otherwise the client the operator typed into is the one
 * whose turn end can mean "done", and it is done when no request to a client is still open:
 * any agent's request, however old, since the operator's later prompts are follow-ups to
 * the same work, not its end, and an agent's request is closed by an answer or by that
 * agent's next actionable request to the same client (9.6, 9.7); and the operator's own latest prompt,
 * when it asked the other client something that has not been answered. The other client's
 * turn ends are the middle of the work: its reply wakes the first client, whose next turn
 * end is the moment. Done rings once, on either side: a word to the operator counts only
 * while nothing has since arrived for its author, and the operator's client is done only
 * when the last thing to reach it was a completing reply or nothing at all, so a wake that
 * brings context and draws no new word never rings again for what already did.
 * Acknowledgements are receipts and never count as a word to anyone.
 */
@Singleton
class JournalAttention implements Attention {

    private final Journal journal;

    JournalAttention(Journal journal) {
        this.journal = journal;
    }

    @Override
    public Verdict atTurnEnd(Path stateDirectory, Role role) {
        List<Entry> entries = journal.readAfter(stateDirectory, 0).entries();
        Entry prompt = null;
        for (Entry entry : entries) {
            if (entry.metadata().from().isHuman()) {
                prompt = entry;
            }
        }
        if (prompt == null) {
            return new Verdict(true, "nothing from the operator in the discussion");
        }
        ParticipantId self = ParticipantId.of(role);
        Entry latest = null;
        Entry heard = null; // the last entry that reached this client, acks aside
        for (Entry entry : entries) {
            EntryMetadata m = entry.metadata();
            if (entry.seq() <= prompt.seq() || m.type() == MessageType.ACK) {
                continue;
            }
            if (m.from().equals(self)) {
                latest = entry;
            } else if (m.addresses(self)) {
                heard = entry;
            }
        }
        long heardAt = heard == null ? 0 : heard.seq();
        if (latest != null && latest.seq() > heardAt && operatorAlone(latest.metadata())) {
            return new Verdict(true, role.displayName() + "'s latest word since the operator's last prompt went to the operator alone");
        }
        Map<String, List<EntryMetadata>> responses = JournalPending.responses(entries);
        Role via = prompt.metadata().via();
        if (via != role) {
            return new Verdict(false, "the operator's last prompt was typed into "
                    + (via == null ? "no client" : via.displayName()) + ", not " + role.displayName());
        }
        long open = 0;
        for (Entry entry : entries) {
            if (!entry.metadata().isAgentAuthored() && entry.seq() < prompt.seq()) {
                continue; // an earlier prompt of the operator's is not this task's open question
            }
            for (Role recipient : Role.values()) {
                if (open(entry, recipient, entries, responses)) {
                    open++;
                    break;
                }
            }
        }
        if (open > 0) {
            return new Verdict(false, open + (open == 1 ? " open request" : " open requests") + " to a client");
        }
        // Done rings once: on the turn that handled the peer's completing reply, or on the client's own
        // work. Context that arrives afterwards and closes nothing starts a turn that must stay quiet.
        if (heard != null && (latest == null || latest.seq() < heard.seq()) && !completes(heard, entries, responses)) {
            return new Verdict(false, "nothing is open, but the last thing to reach " + role.displayName()
                    + " was context from " + heard.metadata().from().displayName() + ", which already rang");
        }
        return new Verdict(true, "nothing is open");
    }

    /**
     * A reply that ended a delegation: it answers, by the closure rule, a request that was
     * still open when it arrived, neither answered before nor superseded. A reply to context,
     * or a late reply to a request already closed, completes nothing and is context itself.
     */
    private static boolean completes(Entry reply, List<Entry> entries, Map<String, List<EntryMetadata>> responses) {
        EntryMetadata m = reply.metadata();
        if (m.type() != MessageType.REPLY || m.expectsReply()) {
            return false;
        }
        Map<String, Entry> byId = new HashMap<>();
        for (Entry entry : entries) {
            byId.put(entry.metadata().id(), entry);
        }
        List<Entry> before = entries.stream().filter(e -> e.seq() < reply.seq()).toList();
        for (Map.Entry<String, List<EntryMetadata>> answered : responses.entrySet()) {
            Entry request = byId.get(answered.getKey());
            if (request == null || answered.getValue().stream().noneMatch(r -> r.id().equals(m.id()))
                    || !JournalPending.answers(m, request.metadata())) {
                continue;
            }
            // Each recipient answers for itself, as pending judges it: another recipient's earlier
            // answer to the same request, a broadcast's, leaves this one's work open until now.
            boolean answeredBefore = answered.getValue().stream().anyMatch(r -> !r.id().equals(m.id())
                    && r.from().equals(m.from()) && byId.containsKey(r.id()) && byId.get(r.id()).seq() < reply.seq()
                    && JournalPending.answers(r, request.metadata()));
            boolean superseded = m.from().role().map(recipient -> JournalPending.superseded(request, recipient, before)).orElse(false);
            if (!answeredBefore && !superseded) {
                return true;
            }
        }
        return false;
    }

    /** Addressed to the operator and to no client: the author wants the operator, not a peer. */
    private static boolean operatorAlone(EntryMetadata metadata) {
        return !metadata.to().isEmpty() && metadata.to().stream().allMatch(ParticipantId::isHuman);
    }

    /** An actionable entry the recipient must still answer, by the rule {@code pending} lists it. */
    private static boolean open(Entry entry, Role recipient, List<Entry> entries, Map<String, List<EntryMetadata>> responses) {
        EntryMetadata metadata = entry.metadata();
        if (!Addressing.concerns(metadata, recipient) || metadata.type() == MessageType.ACK || !metadata.expectsReply()) {
            return false;
        }
        if (JournalPending.superseded(entry, recipient, entries)) {
            return false;
        }
        ParticipantId self = ParticipantId.of(recipient);
        return responses.getOrDefault(metadata.id(), List.of()).stream()
                .noneMatch(r -> r.from().equals(self) && JournalPending.answers(r, metadata));
    }
}
