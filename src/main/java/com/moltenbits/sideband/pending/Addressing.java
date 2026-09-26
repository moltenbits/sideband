package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;

/** Which entries an instance must look at, derived from metadata alone. */
public final class Addressing {

    private Addressing() {
    }

    /**
     * Addressed to the instance, not authored by it, and not a human turn typed into it. The
     * instance a human typed into acts on that turn directly, so it is never for that instance.
     */
    public static boolean concerns(EntryMetadata metadata, ParticipantId self) {
        if (metadata.from().isHuman() && self.equals(metadata.via())) {
            return false;
        }
        return metadata.addresses(self) && !metadata.from().equals(self);
    }

    /** Sent by the instance and awaiting an answer: any actionable message to a client. */
    public static boolean isOutgoingRequest(EntryMetadata metadata, ParticipantId self) {
        return metadata.from().equals(self) && metadata.expectsReply() && metadata.addressesAnyClient();
    }
}
