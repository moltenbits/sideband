package com.moltenbits.sideband.install;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * What is installed where. States: installed, updated, unchanged, missing, stale, conflict,
 * ejected; and for {@code inbound}, Claude Code's delivery setting: added, unchanged, kept
 * (an explicit choice left alone), installed, held, refused, missing. An item's {@code note},
 * when present, says what the state does and does not cover.
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
