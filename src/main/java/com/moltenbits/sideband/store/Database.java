package com.moltenbits.sideband.store;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Opens the store for one unit of work. A command is a short-lived process, so each unit
 * opens its own connection and closes it; SQLite's own file locking serializes readers
 * and writers across processes, each waiting its turn up to the busy timeout. The default
 * rollback journal is kept: switching a connection to write-ahead logging is a pragma that
 * fails at once, without waiting, while another connection writes.
 * <p>
 * The database is created, with its schema, by the first write. A read finds no database,
 * or one another process has created but not yet furnished, and reports absence instead.
 */
@Singleton
final class Database {

    private static final String SCHEMA_RESOURCE = "schema.sql";

    private final Settings settings = new Settings().withRenderSchema(false).withExecuteLogging(false);
    /** How long a writer waits for another process's lock before giving up. */
    private final Duration busyTimeout;
    private final NativeLibrary library;

    Database(@Value("${sideband.store.busy-timeout:10s}") Duration busyTimeout, NativeLibrary library) {
        this.busyTimeout = busyTimeout;
        this.library = library;
    }

    /** Runs {@code work} against an existing store, or returns {@code whenAbsent} when there is none. */
    <T> T read(Path stateDirectory, T whenAbsent, Function<DSLContext, T> work) {
        Path file = file(stateDirectory);
        if (!Files.exists(file)) {
            return whenAbsent;
        }
        try (Connection connection = open(file)) {
            DSLContext ctx = context(connection);
            if (version(ctx) < Schema.VERSION) {
                return whenAbsent;
            }
            return work.apply(ctx);
        } catch (SQLException | DataAccessException e) {
            throw translate(file, e);
        }
    }

    /** Runs {@code work} in one transaction, creating the store first when it does not exist yet. */
    <T> T write(Path stateDirectory, Function<DSLContext, T> work) {
        Path file = file(stateDirectory);
        try (Connection connection = open(file)) {
            DSLContext ctx = context(connection);
            return ctx.transactionResult(tx -> {
                DSLContext inner = tx.dsl();
                if (version(inner) < Schema.VERSION) {
                    install(inner);
                }
                return work.apply(inner);
            });
        } catch (SQLException | DataAccessException e) {
            throw translate(file, e);
        }
    }

    static Path file(Path stateDirectory) {
        return stateDirectory.resolve(Store.FILE_NAME);
    }

    private Connection open(Path file) throws SQLException {
        library.prepare();
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout((int) busyTimeout.toMillis());
        // A transaction takes the write lock as it begins, so a read inside it never has to upgrade.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return config.createConnection("jdbc:sqlite:" + file);
    }

    private DSLContext context(Connection connection) {
        return DSL.using(connection, SQLDialect.SQLITE, settings);
    }

    /** Pragmas have no typed form, so these two statements are the store's only literal SQL besides the DDL. */
    private static int version(DSLContext ctx) {
        return ((Number) ctx.fetchValue("PRAGMA user_version")).intValue();
    }

    private static void install(DSLContext ctx) {
        for (String statement : schema()) {
            ctx.execute(statement);
        }
        ctx.execute("PRAGMA user_version = " + Schema.VERSION);
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

    private RuntimeException translate(Path file, Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLiteException sqlite && isBusy(sqlite.getResultCode())) {
                return new BusyException(file, busyTimeout, e);
            }
        }
        return new UncheckedIOException("could not use " + file + ": " + e.getMessage(), new IOException(e));
    }

    private static boolean isBusy(SQLiteErrorCode code) {
        return code != null && (code.name().startsWith("SQLITE_BUSY") || code.name().startsWith("SQLITE_LOCKED"));
    }
}
