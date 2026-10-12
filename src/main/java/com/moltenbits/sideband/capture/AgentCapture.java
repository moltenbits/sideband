package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.host.HostProcess;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Records what an agent said in its own terminal, from its client's hooks, so the agent never
 * has to (REQUIREMENTS.md 7.5, 14.16a). Each hook reads the session's transcript from where
 * the last capture stopped and journals every message found there once, in order, one entry
 * per turn, as a {@link com.moltenbits.sideband.protocol.Draft#capturedTurn captured turn}.
 * Only a session that holds an instance is recorded, as only it records prompts.
 */
public interface AgentCapture {

    /**
     * What a client's hook knows of the session it fired in.
     *
     * @param turn       the turn the hook fired for, as the host identifies it: Claude Code's {@code prompt_id}, Codex's {@code turn_id}
     * @param transcript the session's transcript, as the payload names it
     */
    record Hook(Role client, String sessionId, @Nullable String turn, @Nullable Path transcript, @Nullable HostProcess caller) {
    }

    /**
     * The operator is about to speak in the session: whatever the agent said since the last
     * capture, an interrupted turn included, is recorded first, so it precedes the prompt. In
     * a session that holds no instance, nothing is recorded, and a later capture starts here,
     * so nothing said before the session joined is ever recorded.
     */
    List<Entry> beforePrompt(Path stateDirectory, Hook hook);

    /** The prompt that starts {@code hook.turn()} was recorded as {@code entryId}; the turn's words will name it as their cause. */
    void prompted(Path stateDirectory, Hook hook, String entryId);

    /**
     * The turn ended. Waits a moment for the turn's final message when the transcript does not
     * hold it yet. When the transcript cannot be read and nothing of the turn's has been
     * recorded, {@code lastMessage}, the host's copy of the final message, is recorded instead.
     */
    List<Entry> turnEnded(Path stateDirectory, Hook hook, @Nullable String lastMessage);

    /** An interruption or the session's end: records what the transcript holds now, without waiting. */
    List<Entry> flush(Path stateDirectory, Hook hook);
}
