package com.moltenbits.sideband.store

import org.sqlite.SQLiteJDBCLoader
import org.sqlite.util.LibraryLoaderUtil
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class NativeLibrarySpec extends Specification {

    Path cache = Files.createTempDirectory("cache")
    NativeLibrary library = new NativeLibrary(cache.toString())
    String name = LibraryLoaderUtil.getNativeLibName()

    def cleanup() {
        System.clearProperty("org.sqlite.lib.path")
        System.clearProperty("org.sqlite.lib.name")
    }

    void "the library is kept per user and driver version, under the cache directory"() {
        expect:
        library.directory() == cache.resolve("sideband").resolve("sqlite-jdbc-" + SQLiteJDBCLoader.getVersion())
    }

    void "preparing writes the bundled library once and points the driver at it"() {
        when:
        library.prepare()
        Path copy = library.directory().resolve(name)

        then:
        Files.isRegularFile(copy)
        Files.size(copy) > 100_000
        System.getProperty("org.sqlite.lib.path") == copy.parent.toString()
        System.getProperty("org.sqlite.lib.name") == name
        Files.list(copy.parent).count() == 1

        when: "a second component finds the copy and rewrites nothing"
        def modified = Files.getLastModifiedTime(copy)
        new NativeLibrary(cache.toString()).prepare()

        then:
        Files.getLastModifiedTime(copy) == modified
    }

    void "the copy is byte for byte the library bundled with the driver"() {
        given:
        byte[] bundled = NativeLibrary.getResourceAsStream(LibraryLoaderUtil.getNativeLibResourcePath() + "/" + name).bytes

        when:
        library.prepare()

        then:
        Files.readAllBytes(library.directory().resolve(name)) == bundled
    }

    void "an unwritable cache leaves the driver to its own extraction"() {
        given:
        Path file = Files.writeString(cache.resolve("not-a-directory"), "x")
        NativeLibrary blocked = new NativeLibrary(file.toString())

        when:
        blocked.prepare()

        then:
        noExceptionThrown()
        System.getProperty("org.sqlite.lib.path") == null
    }

    void "without a configured directory the cache is XDG_CACHE_HOME or ~/.cache"() {
        expect:
        new NativeLibrary("").directory().toString().contains("sideband/sqlite-jdbc-")
        new NativeLibrary(null).directory().startsWith(
                System.getenv("XDG_CACHE_HOME") ? Path.of(System.getenv("XDG_CACHE_HOME")) : Path.of(System.getProperty("user.home"), ".cache"))
    }
}
