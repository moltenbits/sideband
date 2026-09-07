package com.moltenbits.sideband.store;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.connection.ConnectionOperations;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Enters a state directory's database for one unit of work. Reads run the repositories on
 * their own connections; a write runs them inside one transaction, which SQLite serializes
 * against every other writer across processes, each waiting its turn up to the busy
 * timeout. The default rollback journal is kept: switching a connection to write-ahead
 * logging is a pragma that fails at once, without waiting, while another connection writes.
 * <p>
 * The database is created, with its schema, by the first write, which also imports a
 * journal written before the store existed. A read finds no database, or one another
 * process has created but not yet furnished, and reports absence instead.
 */
@Singleton
final class Database {

    /** {@code PRAGMA user_version} once the schema in {@code schema.sql} is installed. */
    static final int SCHEMA_VERSION = 1;

    private static final String SCHEMA_RESOURCE = "schema.sql";

    /** The data source's busy timeout, repeated here for the contention message; the data source itself is reached through the repositories. */
    private final Duration busyTimeout;
    private final ConnectionOperations<Connection> connections;
    private final TransactionOperations<Connection> transactions;
    private final LegacyImport legacy;

    Database(@Value("${sideband.store.busy-timeout:10s}") Duration busyTimeout,
             @Named("default") ConnectionOperations<Connection> connections,
             @Named("default") TransactionOperations<Connection> transactions,
             LegacyImport legacy) {
        this.busyTimeout = busyTimeout;
        this.connections = connections;
        this.transactions = transactions;
        this.legacy = legacy;
    }

    /**
     * Runs {@code work} against an existing store, or returns {@code whenAbsent} when there is
     * none. A journal from before the store counts as an existing store: it is imported first.
     */
    <T> T read(Path stateDirectory, T whenAbsent, Supplier<T> work) {
        Path file = SidebandDataSource.file(stateDirectory);
        if (!Files.exists(file)) {
            if (!legacy.present(stateDirectory)) {
                return whenAbsent;
            }
            write(stateDirectory, () -> null);
        }
        return guarded(file, () -> SidebandDataSource.in(stateDirectory, () -> {
            int version = connections.executeRead(status -> version(status.getConnection()));
            return version < SCHEMA_VERSION ? whenAbsent : work.get();
        }));
    }

    /** Runs {@code work} in one transaction, creating the store first, and importing an older journal into it, when it does not exist yet. */
    <T> T write(Path stateDirectory, Supplier<T> work) {
        Path file = SidebandDataSource.file(stateDirectory);
        return guarded(file, () -> SidebandDataSource.in(stateDirectory, () -> transactions.executeWrite(status -> {
            Connection connection = status.getConnection();
            if (version(connection) < SCHEMA_VERSION) {
                install(connection);
                legacy.run(stateDirectory);
            }
            return work.get();
        })));
    }

    /** Pragmas have no repository form, so these are the store's only statements outside the DDL. */
    static int version(Connection connection) {
        return Integer.parseInt(pragma(connection, "PRAGMA user_version"));
    }

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

    private static void install(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : schema()) {
                statement.execute(ddl);
            }
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /** The DDL, one statement per blank-line-separated block, comments removed. */
    static List<String> schema() {
        try (InputStream in = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + SCHEMA_RESOURCE);
            }
            String text = new String(in.readAllBytes(), UTF_8).lines()
                    .filter(line -> !line.startsWith("--"))
                    .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
                    .toString();
            return Arrays.stream(text.split("\n\\s*\n")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
