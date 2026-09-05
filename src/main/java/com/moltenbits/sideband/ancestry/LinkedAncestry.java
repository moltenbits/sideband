package com.moltenbits.sideband.ancestry;

import com.moltenbits.sideband.protocol.EntryMetadata;
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
            if (delegation) {
                depth++;
            }
            if (!parent.isAgentAuthored()) {
                return new Lineage.Rooted(depth, parent.id());
            }
            current = parent;
        }
    }

    private static boolean applies(EntryMetadata entry) {
        return entry.isAgentAuthored() && entry.expectsReply() && entry.addressesAnyClient();
    }
}
