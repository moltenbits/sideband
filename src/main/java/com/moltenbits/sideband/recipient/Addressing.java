package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;

/** Which entries concern a role. */
public final class Addressing {

    private Addressing() {
    }

    /** Addressed to the role and not authored by it. */
    public static boolean concerns(EntryMetadata metadata, Role role) {
        ParticipantId self = ParticipantId.of(role);
        return metadata.addresses(self) && !metadata.from().equals(self);
    }

    /** Sent by the role and awaiting an answer: any actionable message to a client. */
    public static boolean isOutgoingRequest(EntryMetadata metadata, Role role) {
        return metadata.from().equals(ParticipantId.of(role)) && metadata.expectsReply() && metadata.addressesAnyClient();
    }
}
