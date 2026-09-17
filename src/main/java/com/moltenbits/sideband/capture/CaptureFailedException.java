package com.moltenbits.sideband.capture;

import com.moltenbits.sideband.journal.Entry;
import io.micronaut.core.annotation.Nullable;

/**
 * A capture that did not complete, saying how far it got. The journal append is the step
 * that matters: a failure before it leaves nothing behind and the prompt may be captured
 * again; a failure after it leaves a journaled entry that must not be duplicated; a failure
 * inside it leaves the outcome unknown until the journal is inspected.
 */
public class CaptureFailedException extends RuntimeException {

    public enum Stage {
        /** Nothing was written. */
        NOT_JOURNALED,
        /** The append itself failed; the journal may or may not hold the entry. */
        UNCERTAIN,
        /** The entry is in the journal; a later step (cursor update or delivery) failed. */
        JOURNALED
    }

    private final Stage stage;
    private final @Nullable String journaledId;
    private final @Nullable Entry journaled;

    public CaptureFailedException(Stage stage, @Nullable String journaledId, Throwable cause) {
        this(stage, journaledId, null, cause);
    }

    private CaptureFailedException(Stage stage, @Nullable String journaledId, @Nullable Entry journaled, Throwable cause) {
        super(cause.getMessage(), cause);
        this.stage = stage;
        this.journaledId = journaledId;
        this.journaled = journaled;
    }

    /** A failure after the append, with the entry that is in the journal. */
    public static CaptureFailedException afterAppend(Entry journaled, Throwable cause) {
        return new CaptureFailedException(Stage.JOURNALED, journaled.metadata().id(), journaled, cause);
    }

    public Stage stage() {
        return stage;
    }

    /** The journaled entry's id when the append succeeded, otherwise null. */
    public @Nullable String journaledId() {
        return journaledId;
    }

    /** The journaled entry itself when the failure came after the append and the entry was at hand, otherwise null. */
    public @Nullable Entry journaled() {
        return journaled;
    }
}
