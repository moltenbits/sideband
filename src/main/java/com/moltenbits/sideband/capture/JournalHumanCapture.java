package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.Pushes;
import com.moltenbits.sideband.routing.Destination;
import com.moltenbits.sideband.routing.Routing;
import com.moltenbits.sideband.session.HeldPrompts;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Optional;

@Singleton
class JournalHumanCapture implements HumanCapture {

    private final Journal journal;
    private final Routing routing;
    private final Pushes pushes;
    private final HeldPrompts held;

    JournalHumanCapture(Journal journal, Routing routing, Pushes pushes, HeldPrompts held) {
        this.journal = journal;
        this.routing = routing;
        this.pushes = pushes;
        this.held = held;
    }

    @Override
    public Captured capture(Path stateDirectory, Role via, String body) {
        Destination destination;
        try {
            destination = routing.resolve(body, via);
        } catch (IllegalArgumentException e) {
            throw e; // invalid input keeps its own exit code; nothing was written
        } catch (RuntimeException e) {
            throw new CaptureFailedException(CaptureFailedException.Stage.NOT_JOURNALED, null, e);
        }
        Entry entry;
        try {
            entry = journal.append(stateDirectory, Draft.humanRequest(via, destination.to(), body));
        } catch (RuntimeException e) {
            throw new CaptureFailedException(CaptureFailedException.Stage.UNCERTAIN, null, e);
        }
        return deliver(stateDirectory, entry);
    }

    @Override
    public Optional<Captured> adopt(Path stateDirectory, Role via, String sessionId) {
        Optional<Entry> entry;
        try {
            entry = held.adopt(stateDirectory, via, sessionId,
                    prompt -> Draft.humanRequest(via, routing.resolve(prompt, via).to(), prompt));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CaptureFailedException(CaptureFailedException.Stage.UNCERTAIN, null, e);
        }
        return entry.map(e -> deliver(stateDirectory, e));
    }

    private Captured deliver(Path stateDirectory, Entry entry) {
        try {
            // The client the human typed into acts on this turn directly; the via rule keeps it from being redelivered.
            return Captured.of(entry, pushes.deliver(stateDirectory, entry));
        } catch (RuntimeException e) {
            throw CaptureFailedException.afterAppend(entry, e);
        }
    }
}
