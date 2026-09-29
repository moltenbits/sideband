package com.moltenbits.sideband.ancestry;

import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import jakarta.inject.Singleton;

import java.util.HashSet;
import java.util.Set;

/** Walks the immediate-cause links recorded in metadata. */
@Singleton
class LinkedAncestry implements Ancestry {

    @Override
    public Lineage trace(EntryMetadata entry, EntryIndex index) {
        if (!applies(entry)) {
            return new Lineage.Exempt();
        }
        Set<String> visited = new HashSet<>();
        visited.add(entry.id());
        EntryMetadata current = entry;
        int depth = 0;
        while (true) {
            boolean delegation = current.causedBy() != null;
            String parentId = delegation ? current.causedBy() : current.replyTo();
            if (parentId == null) {
                throw new InvalidLineageException("entry " + entry.id() + " has no path to a human-authored entry: "
                        + (current == entry ? "it" : current.id()) + " has neither caused_by nor reply_to");
            }
            if (!visited.add(parentId)) {
                throw new InvalidLineageException("entry " + entry.id() + " has a cycle in its causal links at " + parentId);
            }
            EntryMetadata parent = index.find(parentId).orElseThrow(() -> new InvalidLineageException(
                    "entry " + entry.id() + " references missing ancestor " + parentId));
            if (delegation && !continuesOwnExchange(current, parent, index)) {
                depth++;
            }
            if (!parent.isAgentAuthored()) {
                return new Lineage.Rooted(depth, parent.id());
            }
            current = parent;
        }
    }

    /**
     * Whether {@code entry}, linked by {@code caused_by} to {@code cause}, continues its author's own
     * exchange: the cause is a reply to something the same author wrote, as when a reviewer's
     * findings lead to a re-review or the next commit's review. That is thread iteration, which
     * is unbounded, not a delegation in service of someone else's request (REQUIREMENTS.md 8.3).
     */
    private static boolean continuesOwnExchange(EntryMetadata entry, EntryMetadata cause, EntryIndex index) {
        return cause.type() == MessageType.REPLY && cause.replyTo() != null
                && index.find(cause.replyTo()).map(answered -> answered.from().equals(entry.from())).orElse(false);
    }

    private static boolean applies(EntryMetadata entry) {
        return entry.isAgentAuthored() && entry.expectsReply() && entry.addressesAnyClient();
    }
}
