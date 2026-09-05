package com.moltenbits.sideband.journal;

import jakarta.inject.Singleton;

import java.util.UUID;

@Singleton
class UuidMessageIds implements MessageIds {

    @Override
    public String next() {
        return UUID.randomUUID().toString();
    }
}
