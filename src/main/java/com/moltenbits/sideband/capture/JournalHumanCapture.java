package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.Pushes;
import com.moltenbits.sideband.routing.Destination;
import com.moltenbits.sideband.routing.Routing;
import com.moltenbits.sideband.session.HeldPrompts;
import com.moltenbits.sideband.session.Sessions;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

@Singleton
class JournalHumanCapture implements HumanCapture {

    private static final Logger LOG = LoggerFactory.getLogger(JournalHumanCapture.class);

    private final Journal journal;
    private final Routing routing;
    private final Pushes pushes;
    private final HeldPrompts held;
    private final Sessions sessions;
    private final AgentCapture agents;

    JournalHumanCapture(Journal journal, Routing routing, Pushes pushes, HeldPrompts held, Sessions sessions, AgentCapture agents) {
        this.sessions = sessions;
        this.agents = agents;
        this.journal = journal;
        this.routing = routing;
        this.pushes = pushes;
        this.held = held;
    }

    @Override
    public Captured capture(Path stateDirectory, ParticipantId via, String body) {
        Destination destination;
        try {
            destination = routing.resolve(body, via, sessions.all(stateDirectory).keySet());
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
    public Optional<Captured> adopt(Path stateDirectory, ParticipantId via, String sessionId) {
        Role role = via.role().orElseThrow();
        Optional<HeldPrompts.Adopted> adopted;
        try {
            Set<ParticipantId> joined = sessions.all(stateDirectory).keySet();
            adopted = held.adopt(stateDirectory, role, sessionId,
                    prompt -> Draft.humanRequest(via, routing.resolve(prompt, via, joined).to(), prompt));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CaptureFailedException(CaptureFailedException.Stage.UNCERTAIN, null, e);
        }
        adopted.filter(a -> a.turn() != null).ifPresent(a -> causeOf(stateDirectory, role, sessionId, a.turn(), a.entry()));
        return adopted.map(a -> deliver(stateDirectory, a.entry()));
    }

    /**
     * Makes the adopted prompt the cause of the agent's words in the turn it started, as the
     * prompt hook does for a prompt it records. The prompt is recorded either way; if this
     * fails, that turn's words are recorded naming no cause, which is reported on stderr but
     * is no reason to fail the join.
     */
    private void causeOf(Path stateDirectory, Role role, String sessionId, String turn, Entry prompt) {
        try {
            agents.prompted(stateDirectory, new AgentCapture.Hook(role, sessionId, turn, null, null), prompt.metadata().id());
        } catch (RuntimeException e) {
            LOG.warn("could not link adopted prompt {} to its turn {}: {}", prompt.metadata().id(), turn, e.getMessage());
        }
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
