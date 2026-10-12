package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.session.CaptureLedger;
import com.moltenbits.sideband.session.CaptureLedger.Cursor;
import com.moltenbits.sideband.session.CaptureLedger.Mode;
import com.moltenbits.sideband.session.CaptureLedger.State;
import com.moltenbits.sideband.session.CaptureLedger.Turn;
import com.moltenbits.sideband.session.InstanceRule;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The capture contract of REQUIREMENTS.md 14.16a. Each capture loads the session's state,
 * decides from the transcript what to record, and commits only if the state is unchanged
 * and the session is still the instance it decided for; when another hook got there first,
 * it decides again once from what that hook left.
 * <p>
 * A turn's words come from the transcript, every message once, in order: the cursor says
 * where reading resumes, and each message recorded is kept by the identity its host gave it,
 * so a transcript copied to another file or rewritten is read again without recording twice. Only when the transcript cannot be read at a turn's end, and nothing of that
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
    private record Decision(List<Draft> drafts, List<String> messageIds, @Nullable Cursor cursor, List<Turn> turns) {

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
                Decision decision = decide(stateDirectory, state, hook, instance, null);
                if (!decision.changes(state)) {
                    return List.of();
                }
                var committed = ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, instance,
                        decision.drafts(), decision.messageIds(), decision.cursor(), decision.turns());
                if (committed.isPresent()) {
                    return committed.get();
                }
                continue;
            }
            // Nothing is recorded for a session that holds no instance; a later capture starts here instead.
            Cursor here = endOf(hook);
            if (here == null || here.equals(state.cursor())
                    || ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, null,
                    List.of(), List.of(), here, List.of()).isPresent()) {
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
                    List.of(), List.of(), state.cursor(), List.of(prompted)).isPresent()) {
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
            Decision decision = decide(stateDirectory, state, hook, instance, ending);
            if (!decision.changes(state)) {
                return List.of();
            }
            var committed = ledger.commit(stateDirectory, hook.client(), hook.sessionId(), hook.caller(), state, instance,
                    decision.drafts(), decision.messageIds(), decision.cursor(), decision.turns());
            if (committed.isPresent()) {
                return committed.get();
            }
        }
        return List.of();
    }

    private Decision decide(Path stateDirectory, State state, Hook hook, ParticipantId instance, @Nullable Ending ending) {
        Path path = hook.transcript();
        try {
            if (path == null) {
                throw new IOException("the hook payload names no transcript");
            }
            Cursor cursor = state.cursor();
            // Another file than the cursor's, or this one cut short: positions read before no longer apply.
            boolean moved = cursor != null && (!cursor.transcript().equals(path) || Files.size(path) < cursor.position());
            long from;
            String turnAt = null;
            if (cursor != null && !moved) {
                from = cursor.position();
                turnAt = cursor.turn();
            } else {
                OptionalLong start = hook.turn() == null || state.turn(hook.turn()).mode() == Mode.PAYLOAD
                        ? OptionalLong.empty() : transcripts.start(hook.client(), path, hook.turn());
                if (start.isPresent()) {
                    from = start.getAsLong();
                } else if (moved) {
                    from = 0; // read all of it: what was recorded before is recognized by its identity
                } else {
                    // Never captured here: what came before this turn is history from before Sideband.
                    Cursor here = new Cursor(path, transcripts.end(path), hook.turn());
                    return ending == null ? new Decision(List.of(), List.of(), here, List.of()) : fromPayload(state, instance, ending, here);
                }
            }
            Excerpt excerpt = transcripts.read(hook.client(), path, from, turnAt);
            long deadline = System.nanoTime() + finalWait.toNanos();
            while (ending != null && !finalSeen(state, excerpt, ending.turn()) && System.nanoTime() < deadline) {
                if (!pause()) {
                    break;
                }
                excerpt = transcripts.read(hook.client(), path, from, turnAt);
            }
            Set<String> recorded = ledger.recorded(stateDirectory, hook.client(), hook.sessionId(),
                    excerpt.messages().stream().map(Message::id).filter(Objects::nonNull).toList());
            return fromTranscript(state, instance, excerpt, path, recorded);
        } catch (IOException e) {
            return ending == null ? new Decision(List.of(), List.of(), state.cursor(), List.of())
                    : fromPayload(state, instance, ending, state.cursor());
        }
    }

    /**
     * Every message read and not recorded before becomes part of its turn's entry, one entry
     * per run of messages from one turn, except a turn whose final was taken from the payload:
     * its records are skipped.
     */
    private static Decision fromTranscript(State state, ParticipantId instance, Excerpt excerpt, Path path, Set<String> recorded) {
        Set<String> seen = new HashSet<>(recorded);
        List<Message> messages = excerpt.messages().stream()
                .filter(m -> m.id() == null || seen.add(m.id()))
                .toList();
        List<Draft> drafts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Map<String, Turn> changed = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); ) {
            String turnId = messages.get(i).turn();
            List<Message> run = new ArrayList<>();
            for (; i < messages.size() && Objects.equals(messages.get(i).turn(), turnId); i++) {
                run.add(messages.get(i));
            }
            Turn turn = turnId == null ? null : changed.getOrDefault(turnId, state.turn(turnId));
            if (turn != null && turn.mode() == Mode.PAYLOAD) {
                continue;
            }
            drafts.add(Draft.capturedTurn(instance, turn == null ? null : turn.promptEntryId(),
                    String.join("\n\n", run.stream().map(Message::text).toList())));
            run.stream().map(Message::id).filter(Objects::nonNull).forEach(ids::add);
            if (turn != null) {
                boolean ended = turn.finalCaptured() || run.stream().anyMatch(Message::endsTurn);
                changed.put(turnId, new Turn(turnId, turn.promptEntryId(), Mode.TRANSCRIPT, ended));
            }
        }
        return new Decision(drafts, ids, new Cursor(path, excerpt.end(), excerpt.turn()), List.copyOf(changed.values()));
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
            return new Decision(List.of(), List.of(), cursor, List.of());
        }
        return new Decision(List.of(Draft.capturedTurn(instance, turn.promptEntryId(), last)), List.of(), cursor,
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

    /**
     * The instance whose record names the session or its process. The commands' rule also
     * names the unnamed instance when the client has no records at all; that lets a command
     * act before any join, but says nothing about a session's words, so it records nothing.
     */
    private @Nullable ParticipantId instance(Path stateDirectory, Hook hook) {
        InstanceRule.Identified identified = sessions.identify(stateDirectory, hook.client(), hook.sessionId(), hook.caller());
        return identified.records().isEmpty() ? null : identified.instance();
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
