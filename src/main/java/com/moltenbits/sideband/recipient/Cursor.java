package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.Map;
import java.util.TreeMap;

/**
 * One client role's local state: its current session, what it has done with incoming
 * entries, and the requests it is waiting on. It is never a communication channel.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record Cursor(
        int schema,
        Role role,
        @Nullable Session session,
        Map<String, EntryState> entries,
        Map<String, OutgoingState> outgoing) {

    public static final int SCHEMA = 1;

    public Cursor {
        entries = Map.copyOf(new TreeMap<>(entries));
        outgoing = Map.copyOf(new TreeMap<>(outgoing));
    }

    public static Cursor empty(Role role) {
        return new Cursor(SCHEMA, role, null, Map.of(), Map.of());
    }

    public EntryState stateOf(String id) {
        return entries.getOrDefault(id, EntryState.NONE);
    }

    public boolean isResolved(String id) {
        return stateOf(id).isResolved();
    }

    Cursor withEntry(String id, EntryState state) {
        Map<String, EntryState> updated = new TreeMap<>(entries);
        updated.put(id, state);
        return new Cursor(schema, role, session, updated, outgoing);
    }

    Cursor withOutgoing(String id, OutgoingState state) {
        Map<String, OutgoingState> updated = new TreeMap<>(outgoing);
        updated.put(id, state);
        return new Cursor(schema, role, session, entries, updated);
    }

    Cursor withSession(Session current) {
        return new Cursor(schema, role, current, entries, outgoing);
    }
}
