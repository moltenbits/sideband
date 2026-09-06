package com.moltenbits.sideband.pending;

import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;

/** Which entries a role must look at, derived from metadata alone. */
public final class Addressing {

    private Addressing() {
    }

    /**
     * Addressed to the role, not authored by it, and not a human turn typed into it. The
     * client a human typed into acts on that turn directly, so it is never for that client.
     */
    public static boolean concerns(EntryMetadata metadata, Role role) {
        ParticipantId self = ParticipantId.of(role);
        if (metadata.from().isHuman() && metadata.via() == role) {
            return false;
        }
        return metadata.addresses(self) && !metadata.from().equals(self);
    }

    /** Sent by the role and awaiting an answer: any actionable message to a client. */
    public static boolean isOutgoingRequest(EntryMetadata metadata, Role role) {
        return metadata.from().equals(ParticipantId.of(role)) && metadata.expectsReply() && metadata.addressesAnyClient();
    }
}
