package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.config.Configs;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.Pushes;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Resolution;
import com.moltenbits.sideband.routing.Destination;
import com.moltenbits.sideband.routing.Routing;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;

@Singleton
class JournalHumanCapture implements HumanCapture {

    private final Journal journal;
    private final Routing routing;
    private final RecipientState recipients;
    private final Configs configs;
    private final Pushes pushes;

    JournalHumanCapture(Journal journal, Routing routing, RecipientState recipients, Configs configs, Pushes pushes) {
        this.journal = journal;
        this.routing = routing;
        this.recipients = recipients;
        this.configs = configs;
        this.pushes = pushes;
    }

    @Override
    public Captured capture(Path stateDirectory, Role via, String body) {
        Destination destination = routing.resolve(body, via);
        ParticipantId human = ParticipantId.human(configs.require(stateDirectory).id());
        Entry entry = journal.append(stateDirectory.resolve(Journal.FILE_NAME),
                Draft.humanInstruction(human, via, destination.to(), body));
        if (destination.to().contains(ParticipantId.of(via))) {
            // The client the human typed into acts on this turn directly; it must never redeliver it.
            recipients.resolve(stateDirectory, via, List.of(entry.metadata().id()), Resolution.ORIGINATING_TURN);
        }
        return Captured.of(entry, pushes.deliver(stateDirectory, entry));
    }
}
