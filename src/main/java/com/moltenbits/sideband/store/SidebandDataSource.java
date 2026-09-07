package com.moltenbits.sideband.store;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.sqlite.SQLiteConfig;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * The one data source the repositories use. A command works on one repository's state
 * directory, resolved from the working directory at run time, so the connection a
 * repository call gets is to whichever database the calling thread has entered with
 * {@link #in}. There is no pool: SQLite opens in microseconds, a command is a short-lived
 * process, and every connection is closed when its unit of work ends.
 */
@Singleton
@Named("default")
final class SidebandDataSource implements DataSource {

    private static final ThreadLocal<Path> CURRENT = new ThreadLocal<>();

    private final Duration busyTimeout;
    private final NativeLibrary library;

    SidebandDataSource(@Value("${sideband.store.busy-timeout:10s}") Duration busyTimeout, NativeLibrary library) {
        this.busyTimeout = busyTimeout;
        this.library = library;
    }

    /** Runs {@code work} with every connection the thread opens meanwhile going to {@code stateDirectory}'s database. */
    static <T> T in(Path stateDirectory, Supplier<T> work) {
        Path previous = CURRENT.get();
        CURRENT.set(stateDirectory);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static Path file(Path stateDirectory) {
        return stateDirectory.resolve(Store.FILE_NAME);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Path stateDirectory = CURRENT.get();
        if (stateDirectory == null) {
            throw new IllegalStateException("no state directory entered on this thread");
        }
        library.prepare();
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout((int) busyTimeout.toMillis());
        // A transaction takes the write lock as it begins, so a read inside it never has to upgrade.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return config.createConnection("jdbc:sqlite:" + file(stateDirectory));
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
