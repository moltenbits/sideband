package com.moltenbits.sideband.store;

import jakarta.inject.Singleton;
import org.sqlite.SQLiteJDBCLoader;
import org.sqlite.util.LibraryLoaderUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps one copy of SQLite's native library in the state directory, beside the database,
 * so the driver loads it in place instead of extracting a fresh copy to a temporary file
 * on every command. The extraction and the operating system's first look at a new library
 * cost more than the rest of a command put together; a hook pays that on every prompt.
 * <p>
 * The file is named with the driver version so an upgrade never loads a stale library.
 * When it cannot be written the driver's own extraction still works.
 */
@Singleton
final class NativeLibrary {

    private boolean prepared;

    /** The file the library is kept in, inside the state directory. */
    static Path file(Path stateDirectory) {
        return stateDirectory.resolve("sqlite-jdbc-" + SQLiteJDBCLoader.getVersion() + "-" + LibraryLoaderUtil.getNativeLibName());
    }

    /**
     * Points the driver at the library in {@code stateDirectory}, writing it first when it is
     * not there yet. The driver loads a library once per process, so this happens once per
     * process too: the first state directory a process opens is where it loads from.
     */
    synchronized void prepare(Path stateDirectory) {
        if (prepared) {
            return;
        }
        prepared = true;
        Path library = file(stateDirectory);
        try {
            if (!Files.exists(library)) {
                write(library);
            }
            System.setProperty("org.sqlite.lib.path", library.getParent().toString());
            System.setProperty("org.sqlite.lib.name", library.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            // Leave the driver to its own extraction; slower, never wrong.
        }
    }

    /** Writes the bundled library beside its final name and moves it into place, so a concurrent reader never sees a partial file. */
    private static void write(Path library) throws IOException {
        String resource = LibraryLoaderUtil.getNativeLibResourcePath() + "/" + LibraryLoaderUtil.getNativeLibName();
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
}
