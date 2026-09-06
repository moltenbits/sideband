package com.moltenbits.sideband.session;

import com.moltenbits.sideband.protocol.WireSerde;
import jakarta.inject.Singleton;

@Singleton
final class SessionSerdes extends WireSerde<Delivery> {

    SessionSerdes() {
        super(Delivery.class);
    }
}
