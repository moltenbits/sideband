package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.session.CaptureLedger;
import com.moltenbits.sideband.session.CaptureLedger.Cursor;
import com.moltenbits.sideband.session.CaptureLedger.Mode;
import com.moltenbits.sideband.session.CaptureLedger.State;
import com.moltenbits.sideband.session.CaptureLedger.Turn;
import com.moltenbits.sideband.session.Sessions;
import com.moltenbits.sideband.transcript.Excerpt;
import com.moltenbits.sideband.transcript.Message;
import com.moltenbits.sideband.transcript.Transcripts;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The capture contract of REQUIREMENTS.md 14.16a. Each capture loads the session's state,
 * decides from the transcript what to record, and commits only if the state is unchanged
 * and the session is still the instance it decided for; when another hook got there first,
 * it decides again once from what that hook left.
 * <p>
 * A turn's words come from the transcript, every message once, in order: the cursor is the
 * receipt. Only when the transcript cannot be read at a turn's end, and nothing of that
 * turn's has been recorded, is the host's copy of its final message recorded instead; the
 * turn's receipt then says so, and its transcript records are skipped for good.
 */
@Singleton
class JournalAgentCapture implements AgentCapture {

    /** A capture that lost a race decides once more, from the state the winner left. */
    private static final int ATTEMPTS = 2;

    private final Transcripts transcripts;
    private final CaptureLedger ledger;
    private final Sessions sessions;
    private final Duration finalWait;
    private final Duration pollInterval;

    JournalAgentCapture(Transcripts transcripts, CaptureLedger ledger, Sessions sessions,
                        @Value("${sideband.capture.final-wait:2s}") Duration finalWait,
                        @Value("${sideband.capture.poll-interval:100ms}") Duration pollInterval) {
        this.transcripts = transcripts;
        this.ledger = ledger;
        this.sessions = sessions;
        this.finalWait = finalWait;
        this.pollInterval = pollInterval;
    }

    /** What a capture will commit: the cursor and turns to write, and the entries to journal. */
    private record Decision(List<Draft> drafts, @Nullable Cursor cursor, List<Turn> turns) {

        boolean changes(State state) {
            return !drafts.isEmpty() || !turns.isEmpty() || !Objects.equals(cursor, state.cursor());
        }
    }

    /** At a turn's end: the turn, and the host's copy of its final message. */
    private record Ending(String turn, @Nullable String lastMessage) {
    }

    @Override
    public List<Entry> beforePrompt(Path stateDirectory, Hook hook) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            State state = ledger.load(stateDirectory, hook.client(), hook.sessionId());
            ParticipantId instance = instance(stateDirectory, hook);
            if (instance != null) {
                Decision decision = decide(state, hook, instance, null);
                if (!decision.changes(state)) {
                    return List.of();
                }
                var committed = ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, instance,
                        decision.drafts(), decision.cursor(), decision.turns());
                if (committed.isPresent()) {
                    return committed.get();
                }
                continue;
            }
            // Nothing is recorded for a session that holds no instance; a later capture starts here instead.
            Cursor here = endOf(hook);
            if (here == null || here.equals(state.cursor())
                    || ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, null,
                    List.of(), here, List.of()).isPresent()) {
                return List.of();
            }
        }
        return List.of();
    }

    @Override
    public void prompted(Path stateDirectory, Hook hook, String entryId) {
        if (hook.turn() == null) {
            return;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            State state = ledger.load(stateDirectory, hook.client(), hook.sessionId());
            Turn turn = state.turn(hook.turn());
            Turn prompted = new Turn(turn.id(), entryId, turn.mode(), turn.finalCaptured());
            if (ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, null,
                    List.of(), state.cursor(), List.of(prompted)).isPresent()) {
                return;
            }
        }
    }

    @Override
    public List<Entry> turnEnded(Path stateDirectory, Hook hook, @Nullable String lastMessage) {
        return capture(stateDirectory, hook, hook.turn() == null ? null : new Ending(hook.turn(), lastMessage));
    }

    @Override
    public List<Entry> flush(Path stateDirectory, Hook hook) {
        return capture(stateDirectory, hook, null);
    }

    private List<Entry> capture(Path stateDirectory, Hook hook, @Nullable Ending ending) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            State state = ledger.load(stateDirectory, hook.client(), hook.sessionId());
            ParticipantId instance = instance(stateDirectory, hook);
            if (instance == null) {
                return List.of();
            }
            Decision decision = decide(state, hook, instance, ending);
            if (!decision.changes(state)) {
                return List.of();
            }
            var committed = ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, instance,
                    decision.drafts(), decision.cursor(), decision.turns());
            if (committed.isPresent()) {
                return committed.get();
            }
        }
        return List.of();
    }

    private Decision decide(State state, Hook hook, ParticipantId instance, @Nullable Ending ending) {
        Path path = hook.transcript();
        try {
            if (path == null) {
                throw new IOException("the hook payload names no transcript");
            }
            long size = Files.size(path);
            Cursor cursor = state.cursor();
            if (cursor != null && (!cursor.transcript().equals(path) || size < cursor.position())) {
                cursor = null; // another file, or this one cut short: nothing read before applies
            }
            long from;
            String turnAt;
            if (cursor != null) {
                from = cursor.position();
                turnAt = cursor.turn();
            } else {
                OptionalLong start = hook.turn() == null || state.turn(hook.turn()).mode() == Mode.PAYLOAD
                        ? OptionalLong.empty() : transcripts.start(hook.client(), path, hook.turn());
                if (start.isEmpty()) {
                    Cursor here = new Cursor(path, transcripts.end(path), hook.turn());
                    return ending == null ? new Decision(List.of(), here, List.of()) : fromPayload(state, instance, ending, here);
                }
                from = start.getAsLong();
                turnAt = null;
            }
            Excerpt excerpt = transcripts.read(hook.client(), path, from, turnAt);
            long deadline = System.nanoTime() + finalWait.toNanos();
            while (ending != null && !finalSeen(state, excerpt, ending.turn()) && System.nanoTime() < deadline) {
                if (!pause()) {
                    break;
                }
                excerpt = transcripts.read(hook.client(), path, from, turnAt);
            }
            return fromTranscript(state, instance, excerpt, path);
        } catch (IOException e) {
            return ending == null ? new Decision(List.of(), state.cursor(), List.of()) : fromPayload(state, instance, ending, state.cursor());
        }
    }

    /**
     * Every message read becomes part of its turn's entry, one entry per run of messages from
     * one turn, except a turn whose final was taken from the payload: its records are skipped.
     */
    private static Decision fromTranscript(State state, ParticipantId instance, Excerpt excerpt, Path path) {
        List<Draft> drafts = new ArrayList<>();
        Map<String, Turn> changed = new LinkedHashMap<>();
        List<Message> messages = excerpt.messages();
        for (int i = 0; i < messages.size(); ) {
            String turnId = messages.get(i).turn();
            List<String> said = new ArrayList<>();
            boolean ended = false;
            for (; i < messages.size() && Objects.equals(messages.get(i).turn(), turnId); i++) {
                said.add(messages.get(i).text());
                ended |= messages.get(i).endsTurn();
            }
            Turn turn = turnId == null ? null : changed.getOrDefault(turnId, state.turn(turnId));
            if (turn != null && turn.mode() == Mode.PAYLOAD) {
                continue;
            }
            drafts.add(Draft.capturedTurn(instance, turn == null ? null : turn.promptEntryId(), String.join("\n\n", said)));
            if (turn != null) {
                changed.put(turnId, new Turn(turnId, turn.promptEntryId(), Mode.TRANSCRIPT, turn.finalCaptured() || ended));
            }
        }
        return new Decision(drafts, new Cursor(path, excerpt.end(), excerpt.turn()), List.copyOf(changed.values()));
    }

    /**
     * The transcript cannot give this turn's end. A turn nothing of which has been recorded
     * gets the host's copy of its final message; one already recorded from the transcript
     * waits for the transcript, and one already given its payload final gets nothing more.
     */
    private static Decision fromPayload(State state, ParticipantId instance, Ending ending, @Nullable Cursor cursor) {
        Turn turn = state.turn(ending.turn());
        String last = ending.lastMessage();
        if (turn.mode() != Mode.NONE || last == null || last.isBlank()) {
            return new Decision(List.of(), cursor, List.of());
        }
        return new Decision(List.of(Draft.capturedTurn(instance, turn.promptEntryId(), last)), cursor,
                List.of(new Turn(turn.id(), turn.promptEntryId(), Mode.PAYLOAD, true)));
    }

    private static boolean finalSeen(State state, Excerpt excerpt, String turn) {
        return state.turn(turn).finalCaptured()
                || excerpt.messages().stream().anyMatch(m -> turn.equals(m.turn()) && m.endsTurn());
    }

    /** The end of the session's transcript as a place to start from; null when it cannot be read. */
    private @Nullable Cursor endOf(Hook hook) {
        if (hook.transcript() == null) {
            return null;
        }
        try {
            return new Cursor(hook.transcript(), transcripts.end(hook.transcript()), hook.turn());
        } catch (IOException e) {
            return null;
        }
    }

    private @Nullable ParticipantId instance(Path stateDirectory, Hook hook) {
        return sessions.identify(stateDirectory, hook.client(), hook.sessionId(), hook.caller()).instance();
    }

    /** Waits one poll interval; false when interrupted, so the caller stops waiting. */
    private boolean pause() {
        try {
            Thread.sleep(pollInterval);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
