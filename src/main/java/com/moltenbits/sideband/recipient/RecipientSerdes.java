package com.moltenbits.sideband.recipient;

import com.moltenbits.sideband.protocol.WireSerde;
import jakarta.inject.Singleton;

final class RecipientSerdes {

    private RecipientSerdes() {
    }

    @Singleton
    static final class ResolutionSerde extends WireSerde<Resolution> {
        ResolutionSerde() {
            super(Resolution.class);
        }
    }

    @Singleton
    static final class OutgoingStatusSerde extends WireSerde<OutgoingStatus> {
        OutgoingStatusSerde() {
            super(OutgoingStatus.class);
        }
    }
}
