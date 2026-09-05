package com.moltenbits.sideband.journal;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/** The wall clock, injected so specifications can pin timestamps. */
@Factory
class ClockFactory {

    @Singleton
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
