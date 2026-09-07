package com.moltenbits.sideband.store;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.moltenbits.sideband.store.Schema.ENTRIES;

@Singleton
class SqliteStore implements Store {

    private final Database database;

    SqliteStore(Database database) {
        this.database = database;
    }

    @Override
    public Optional<StoreHealth> inspect(Path stateDirectory) {
        Path file = Database.file(stateDirectory);
        return database.read(stateDirectory, Optional.empty(), ctx -> {
            long entries = ctx.fetchCount(ENTRIES);
            // SQLite's own check; a pragma has no typed form.
            String integrity = String.valueOf(ctx.fetchValue("PRAGMA integrity_check"));
            return Optional.of(new StoreHealth(file.toString(), size(file), entries, integrity));
        });
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
