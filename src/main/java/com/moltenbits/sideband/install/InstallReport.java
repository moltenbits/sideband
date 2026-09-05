package com.moltenbits.sideband.install;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/** What is installed where. States: installed, updated, unchanged, missing, stale, conflict. */
@Serdeable(naming = SnakeCaseStrategy.class)
public record InstallReport(List<Item> skills, Item hook, Item codexHook) {

    @Serdeable(naming = SnakeCaseStrategy.class)
    public record Item(String name, String path, String state) {
    }
}
