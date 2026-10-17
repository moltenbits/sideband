package com.moltenbits.sideband.store;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.connection.ConnectionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Optional;

@Singleton
class SqliteStore implements Store {

    private final Database database;
    private final EntryRows entries;
    private final ConnectionOperations<Connection> connections;

    SqliteStore(Database database, EntryRows entries, @Named("default") ConnectionOperations<Connection> connections) {
        this.database = database;
        this.entries = entries;
        this.connections = connections;
    }

    @Override
    public StoreHealth create(Path stateDirectory) {
        int found = database.writeOpened(stateDirectory, version -> version); // a write migrates an absent or old database before its transaction
        return inspect(stateDirectory, found).orElseThrow(() -> new IllegalStateException("no database after migration in " + stateDirectory));
    }

    @Override
    public Optional<StoreHealth> inspect(Path stateDirectory) {
        return inspect(stateDirectory, null);
    }

    /** @param created the migration {@link #create} found before migrating, which this read would otherwise see as current */
    private Optional<StoreHealth> inspect(Path stateDirectory, @Nullable Integer created) {
        Path file = SidebandDataSource.file(stateDirectory);
        return database.readOpened(stateDirectory, Optional.empty(), found -> {
            long count = entries.count();
            String integrity = connections.executeRead(status -> Database.integrity(status.getConnection()));
            int schema = database.schema();
            int before = created != null ? created : found;
            Integer migratedFrom = before > 0 && before < schema ? before : null;
            return Optional.of(new StoreHealth(file.toString(), size(file), count, integrity, schema, migratedFrom,
                    Database.SCHEMA_VERSION));
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
