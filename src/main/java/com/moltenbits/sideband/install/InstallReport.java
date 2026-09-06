package com.moltenbits.sideband.install;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * What is installed where. States: installed, updated, unchanged, missing, stale, conflict,
 * ejected; and for {@code inbound}, whether Claude Code will deliver a push: installed, held,
 * refused, missing, unknown, unreadable. An item's {@code note}, when present, says what the
 * state covers and what to change.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record InstallReport(List<Item> skills, Item hook, Item codexHook, Item inbound) {

    @Serdeable(naming = SnakeCaseStrategy.class)
    public record Item(String name, String path, String state, @Nullable String note) {

        public Item(String name, String path, String state) {
            this(name, path, state, null);
        }
    }
}
