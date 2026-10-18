package com.moltenbits.sideband.session;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.host.HostProcesses;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/** The rule of {@link InstanceRule} over a project's records, with liveness from the running processes. */
@Singleton
class RecordInstanceRule implements InstanceRule {

    private final HostProcesses processes;

    RecordInstanceRule(HostProcesses processes) {
        this.processes = processes;
    }

    @Override
    public Followed follow(Map<ParticipantId, Session> records, String sessionId, @Nullable HostProcess caller) {
        TreeMap<ParticipantId, Session> after = new TreeMap<>(records);
        Optional<ParticipantId> bySession = namingSession(after, sessionId);
        if (bySession.isPresent()) {
            Session record = after.get(bySession.get());
            if (caller != null && !caller.equals(record.process())) {
                take(after, bySession.get(), record.withProcess(caller));
            }
            return new Followed(bySession.get(), false, List.of(), unmodifiable(after));
        }
        Optional<ParticipantId> byProcess = namingProcess(after, caller);
        if (byProcess.isPresent()) {
            take(after, byProcess.get(), after.get(byProcess.get()).at(sessionId, caller));
            return new Followed(byProcess.get(), true, List.of(), unmodifiable(after));
        }
        List<ParticipantId> gone = after.entrySet().stream()
                .filter(e -> e.getValue().process() == null || !processes.alive(e.getValue().process()))
                .map(Map.Entry::getKey)
                .toList();
        if (gone.size() == 1) {
            ParticipantId continued = gone.getFirst();
            take(after, continued, after.get(continued).at(sessionId, caller));
            return new Followed(continued, true, List.of(), unmodifiable(after));
        }
        return new Followed(null, false, gone.size() > 1 ? gone : List.of(), unmodifiable(after));
    }

    @Override
    public Identified identify(String project, Role client, Map<ParticipantId, Session> records, @Nullable String sessionId,
                               @Nullable HostProcess caller) {
        SortedMap<ParticipantId, Session> own = ofClient(records, client);
        Optional<ParticipantId> found = namingSession(own, sessionId).or(() -> namingProcess(own, caller));
        if (found.isPresent()) {
            return new Identified(found.get(), List.copyOf(own.keySet()));
        }
        if (!own.isEmpty()) {
            return new Identified(null, List.copyOf(own.keySet()));
        }
        ParticipantId fallback = ParticipantId.of(project, client.id());
        return records.containsKey(fallback)
                ? new Identified(null, List.of(), fallback)
                : new Identified(fallback, List.of());
    }

    @Override
    public Joined join(Map<ParticipantId, Session> records, ParticipantId instance, Session session) {
        Session earlier = records.get(instance);
        if (earlier != null && earlier.client() != session.client()) {
            throw new IllegalArgumentException(instance.name().orElseThrow() + " is held by " + earlier.client().displayName()
                    + " in this project, and a name belongs to one client in a project; join under another name");
        }
        String replaced = earlier != null && !earlier.id().equals(session.id()) ? earlier.id() : null;
        TreeMap<ParticipantId, Session> after = new TreeMap<>(records);
        after.entrySet().removeIf(e -> !e.getKey().equals(instance) && e.getValue().client() == session.client()
                && e.getValue().id().equals(session.id()));
        take(after, instance, session);
        return new Joined(unmodifiable(after), replaced);
    }

    @Override
    public SortedMap<ParticipantId, Session> ofClient(Map<ParticipantId, Session> records, Role client) {
        TreeMap<ParticipantId, Session> ofClient = new TreeMap<>(records);
        ofClient.values().removeIf(session -> session.client() != client);
        return ofClient;
    }

    private static Optional<ParticipantId> namingSession(SortedMap<ParticipantId, Session> records, @Nullable String sessionId) {
        return records.entrySet().stream().filter(e -> e.getValue().id().equals(sessionId)).map(Map.Entry::getKey).findFirst();
    }

    private static Optional<ParticipantId> namingProcess(SortedMap<ParticipantId, Session> records, @Nullable HostProcess caller) {
        if (caller == null) {
            return Optional.empty();
        }
        return records.entrySet().stream().filter(e -> caller.equals(e.getValue().process())).map(Map.Entry::getKey).findFirst();
    }

    /** Saves the record and takes its process from every other record of its client. */
    private static void take(SortedMap<ParticipantId, Session> records, ParticipantId instance, Session record) {
        records.put(instance, record);
        if (record.process() == null) {
            return;
        }
        records.replaceAll((other, session) -> !other.equals(instance) && session.client() == record.client()
                && record.process().equals(session.process()) ? session.withProcess(null) : session);
    }

    private static SortedMap<ParticipantId, Session> unmodifiable(SortedMap<ParticipantId, Session> records) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(records));
    }
}
