package com.moltenbits.sideband.push;

import com.moltenbits.sideband.protocol.WireSerde;
import jakarta.inject.Singleton;

@Singleton
final class PushSerdes extends WireSerde<PushOutcome> {

    PushSerdes() {
        super(PushOutcome.class);
    }
}
