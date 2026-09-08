package com.moltenbits.sideband.store

import org.sqlite.SQLiteJDBCLoader
import org.sqlite.util.LibraryLoaderUtil
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class NativeLibrarySpec extends Specification {

    Path state = Files.createTempDirectory("state")
    NativeLibrary library = new NativeLibrary()
    String name = "sqlite-jdbc-" + SQLiteJDBCLoader.getVersion() + "-" + LibraryLoaderUtil.getNativeLibName()

    def cleanup() {
        System.clearProperty("org.sqlite.lib.path")
        System.clearProperty("org.sqlite.lib.name")
    }

    void "the library lives in the state directory, named with the driver version"() {
        expect:
        NativeLibrary.file(state) == state.resolve(name)
    }

    void "preparing writes the bundled library once and points the driver at it"() {
        when:
        library.prepare(state)
        Path copy = state.resolve(name)

        then:
        Files.isRegularFile(copy)
        Files.size(copy) > 100_000
        System.getProperty("org.sqlite.lib.path") == state.toString()
        System.getProperty("org.sqlite.lib.name") == name
        Files.list(state).count() == 1

        when: "a second component finds the copy and rewrites nothing"
        def modified = Files.getLastModifiedTime(copy)
        new NativeLibrary().prepare(state)

        then:
        Files.getLastModifiedTime(copy) == modified
    }

    void "the copy is byte for byte the library bundled with the driver"() {
        given:
        byte[] bundled = NativeLibrary.getResourceAsStream(LibraryLoaderUtil.getNativeLibResourcePath() + "/" + LibraryLoaderUtil.getNativeLibName()).bytes

        when:
        library.prepare(state)

        then:
        Files.readAllBytes(state.resolve(name)) == bundled
    }

    void "a component prepares once per process: the first state directory is the one loaded from"() {
        given:
        Path other = Files.createTempDirectory("other")

        when:
        library.prepare(state)
        library.prepare(other)

        then:
        Files.exists(state.resolve(name))
        !Files.exists(other.resolve(name))
        System.getProperty("org.sqlite.lib.path") == state.toString()
    }

    void "an unwritable state directory leaves the driver to its own extraction"() {
        given:
        Path file = Files.writeString(state.resolve("not-a-directory"), "x")

        when:
        library.prepare(file)

        then:
        noExceptionThrown()
        System.getProperty("org.sqlite.lib.path") == null
    }
}
