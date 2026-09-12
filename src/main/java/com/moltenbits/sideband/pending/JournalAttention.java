package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The rule: the task is whatever the operator asked for last, and the client the operator
 * typed that into is the one whose turn end means "done", once no request written since
 * then is still waiting for an answer from a client. The other client's turn ends are the
 * middle of the work; its reply wakes the first client, whose turn end is the moment. Either
 * client wanting the operator in its own terminal, by writing to the operator alone, is the
 * one exception. Everything before the operator's latest prompt is another task, so a request
 * from it that was never answered holds nothing back.
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
        long start = prompt.seq();
        List<Entry> since = entries.stream().filter(e -> e.seq() >= start).toList();
        ParticipantId self = ParticipantId.of(role);
        if (since.stream().anyMatch(e -> e.metadata().from().equals(self) && operatorAlone(e.metadata()))) {
            return new Verdict(true, role.displayName() + " addressed the operator alone since the operator's last prompt");
        }
        Role via = prompt.metadata().via();
        if (via != role) {
            return new Verdict(false, "the operator's last prompt was typed into "
                    + (via == null ? "no client" : via.displayName()) + ", not " + role.displayName());
        }
        long open = 0;
        Map<String, List<EntryMetadata>> responses = JournalPending.responses(entries);
        for (Role recipient : Role.values()) {
            open += since.stream().filter(e -> open(e.metadata(), recipient, responses)).count();
        }
        if (open > 0) {
            return new Verdict(false, open + (open == 1 ? " open request" : " open requests")
                    + " to a client since the operator's last prompt");
        }
        return new Verdict(true, "nothing is open since the operator's last prompt");
    }

    /** Addressed to the operator and to no client: the author wants the operator, not a peer. */
    private static boolean operatorAlone(EntryMetadata metadata) {
        return !metadata.to().isEmpty() && metadata.to().stream().allMatch(ParticipantId::isHuman);
    }

    /** An actionable entry the recipient must still answer, by the same rule {@code pending} lists it. */
    private static boolean open(EntryMetadata metadata, Role recipient, Map<String, List<EntryMetadata>> responses) {
        if (!Addressing.concerns(metadata, recipient) || metadata.type() == MessageType.ACK || !metadata.expectsReply()) {
            return false;
        }
        ParticipantId self = ParticipantId.of(recipient);
        return responses.getOrDefault(metadata.id(), List.of()).stream()
                .noneMatch(r -> r.from().equals(self) && JournalPending.answers(r, metadata));
    }
}
