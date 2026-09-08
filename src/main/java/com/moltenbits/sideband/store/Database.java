package com.moltenbits.sideband.store;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.connection.ConnectionOperations;
import io.micronaut.flyway.FlywayConfigurationProperties;
import io.micronaut.flyway.FlywayMigrator;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Enters a state directory's database for one unit of work. Reads run the repositories on
 * their own connections; a write runs them inside one transaction, which SQLite serializes
 * against every other writer across processes, each waiting its turn up to the busy
 * timeout. The default rollback journal is kept: switching a connection to write-ahead
 * logging is a pragma that fails at once, without waiting, while another connection writes.
 * <p>
 * The schema is Flyway's: the migrations under {@code db/migration} run when a write finds
 * the database absent or below the current version, which each migration stamps into
 * {@code PRAGMA user_version}, so an up-to-date database costs one pragma to check. A read
 * finds no database, or one another process has created but not yet migrated, and reports
 * absence.
 */
@Singleton
final class Database {

    /** {@code PRAGMA user_version} once the latest migration has run. */
    static final int SCHEMA_VERSION = 1;

    /** The data source's busy timeout, repeated here for the contention message. */
    private final Duration busyTimeout;
    private final DataSource dataSource;
    private final FlywayMigrator migrator;
    private final FlywayConfigurationProperties migrations;
    private final ConnectionOperations<Connection> connections;
    private final TransactionOperations<Connection> transactions;

    Database(@Value("${sideband.store.busy-timeout:10s}") Duration busyTimeout,
             @Named("default") DataSource dataSource,
             DataSourceResolver resolver,
             FlywayMigrator migrator,
             @Named("default") FlywayConfigurationProperties migrations,
             @Named("default") ConnectionOperations<Connection> connections,
             @Named("default") TransactionOperations<Connection> transactions) {
        this.busyTimeout = busyTimeout;
        // The injected bean is Micronaut Data's contextual proxy, usable only inside a connection scope; Flyway needs the real one.
        this.dataSource = resolver.resolve(dataSource);
        this.migrator = migrator;
        this.migrations = migrations;
        this.connections = connections;
        this.transactions = transactions;
    }

    /** Runs {@code work} against an existing store, or returns {@code whenAbsent} when there is none. */
    <T> T read(Path stateDirectory, T whenAbsent, Supplier<T> work) {
        Path file = SidebandDataSource.file(stateDirectory);
        if (!Files.exists(file)) {
            return whenAbsent;
        }
        return guarded(file, () -> SidebandDataSource.in(stateDirectory, () ->
                version() < SCHEMA_VERSION ? whenAbsent : work.get()));
    }

    /** Runs {@code work} in one transaction. A database that is absent or behind is migrated first, on Flyway's own connections. */
    <T> T write(Path stateDirectory, Supplier<T> work) {
        Path file = SidebandDataSource.file(stateDirectory);
        return guarded(file, () -> SidebandDataSource.in(stateDirectory, () -> {
            if (!Files.exists(file) || version() < SCHEMA_VERSION) {
                migrator.run(migrations, dataSource);
            }
            return transactions.executeWrite(status -> work.get());
        }));
    }

    private int version() {
        return connections.executeRead(status -> Integer.parseInt(pragma(status.getConnection(), "PRAGMA user_version")));
    }

    /** SQLite's own check of the file; a pragma has no repository form. */
    static String integrity(Connection connection) {
        return pragma(connection, "PRAGMA integrity_check");
    }

    private static String pragma(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : "";
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /** Turns SQLite's lock timeout into the contention failure and any other database failure into an I/O failure. */
    private <T> T guarded(Path file, Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && isBusy(sqlite.getResultCode())) {
                    throw new BusyException(file, busyTimeout, e);
                }
                if (cause instanceof SQLException) {
                    throw new UncheckedIOException("could not use " + file + ": " + cause.getMessage(), new IOException(e));
                }
            }
            throw e;
        }
    }

    private static boolean isBusy(SQLiteErrorCode code) {
        return code != null && (code.name().startsWith("SQLITE_BUSY") || code.name().startsWith("SQLITE_LOCKED"));
    }
}
