package com.moltenbits.sideband.store;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.sqlite.SQLiteJDBCLoader;
import org.sqlite.util.LibraryLoaderUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps one copy of SQLite's native library per user and version, so the driver loads it
 * in place instead of extracting a fresh copy to a temporary file on every command. The
 * extraction and the operating system's first look at a new library cost more than the
 * rest of a command put together; a hook pays that on every prompt.
 * <p>
 * The copy lives under the user's cache directory ({@code $XDG_CACHE_HOME}, else
 * {@code ~/.cache}), keyed by the driver version so an upgrade never loads a stale
 * library. When the cache cannot be written the driver's own extraction still works.
 */
@Singleton
final class NativeLibrary {

    private final Path cacheDirectory;
    private boolean prepared;

    NativeLibrary(@Value("${sideband.cache-directory:}") String cacheDirectory) {
        this.cacheDirectory = cacheDirectory == null || cacheDirectory.isBlank()
                ? defaultCacheDirectory()
                : Path.of(cacheDirectory);
    }

    /** The directory the library is kept in: the cache, then the driver version. */
    Path directory() {
        return cacheDirectory.resolve("sideband").resolve("sqlite-jdbc-" + SQLiteJDBCLoader.getVersion());
    }

    /** Points the driver at the cached library, writing it first when it is not there yet. Idempotent. */
    synchronized void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        String name = LibraryLoaderUtil.getNativeLibName();
        Path library = directory().resolve(name);
        try {
            if (!Files.exists(library)) {
                write(library);
            }
            System.setProperty("org.sqlite.lib.path", library.getParent().toString());
            System.setProperty("org.sqlite.lib.name", name);
        } catch (IOException | RuntimeException e) {
            // Leave the driver to its own extraction; slower, never wrong.
        }
    }

    /** Writes the bundled library beside its final name and moves it into place, so a concurrent reader never sees a partial file. */
    private static void write(Path library) throws IOException {
        Files.createDirectories(library.getParent());
        String resource = LibraryLoaderUtil.getNativeLibResourcePath() + "/" + library.getFileName();
        try (InputStream in = NativeLibrary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no bundled library at " + resource);
            }
            Path temp = Files.createTempFile(library.getParent(), library.getFileName().toString(), ".part");
            try {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static Path defaultCacheDirectory() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg == null || xdg.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cache")
                : Path.of(xdg);
    }
}
