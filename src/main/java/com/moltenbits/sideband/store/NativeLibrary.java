package com.moltenbits.sideband.store;

import jakarta.inject.Singleton;
import org.sqlite.SQLiteJDBCLoader;
import org.sqlite.util.LibraryLoaderUtil;
import org.sqlite.util.OSInfo;

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
 * The copy sits in a directory named for the driver version and the platform, under the
 * library's own file name. The driver is told only the directory: it loads the file there
 * by its usual name, and when that fails, a damaged copy or one from another machine, it
 * falls back to extracting its bundled library as if nothing had been configured. A
 * process loads the library once, so the first state directory a process opens is where
 * it loads from.
 */
@Singleton
final class NativeLibrary {

    private boolean prepared;

    /** The directory the library is kept in: state directory, driver version, operating system and architecture. */
    static Path directory(Path stateDirectory) {
        String platform = OSInfo.getNativeLibFolderPathForCurrentOS().replace('/', '-');
        return stateDirectory.resolve("sqlite-jdbc-" + SQLiteJDBCLoader.getVersion() + "-" + platform);
    }

    /** The library file, under the name the driver looks for. */
    static Path file(Path stateDirectory) {
        return directory(stateDirectory).resolve(LibraryLoaderUtil.getNativeLibName());
    }

    /** Points the driver at the library in {@code stateDirectory}, writing it first when it is not there yet. Once per process. */
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
}
