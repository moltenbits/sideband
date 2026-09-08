package com.moltenbits.sideband.store;

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
        database.write(stateDirectory, () -> null); // a write migrates an absent or old database before its transaction
        return inspect(stateDirectory).orElseThrow(() -> new IllegalStateException("no database after migration in " + stateDirectory));
    }

    @Override
    public Optional<StoreHealth> inspect(Path stateDirectory) {
        Path file = SidebandDataSource.file(stateDirectory);
        return database.read(stateDirectory, Optional.empty(), () -> {
            long count = entries.count();
            String integrity = connections.executeRead(status -> Database.integrity(status.getConnection()));
            return Optional.of(new StoreHealth(file.toString(), size(file), count, integrity));
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
