package com.moltenbits.sideband.store

import org.sqlite.SQLiteJDBCLoader
import org.sqlite.util.LibraryLoaderUtil
import org.sqlite.util.OSInfo
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NativeLibrarySpec extends Specification {

    Path state = Files.createTempDirectory("state")
    NativeLibrary library = new NativeLibrary()
    String directory = "sqlite-jdbc-" + SQLiteJDBCLoader.getVersion() + "-" + OSInfo.getNativeLibFolderPathForCurrentOS().replace('/', '-')
    String name = LibraryLoaderUtil.getNativeLibName()

    def cleanup() {
        System.clearProperty("org.sqlite.lib.path")
        System.clearProperty("org.sqlite.lib.name")
    }

    void "the library lives in the state directory, in a directory named for the driver version and platform, under its own name"() {
        expect:
        NativeLibrary.file(state) == state.resolve(directory).resolve(name)
    }

    void "preparing writes the bundled library once and points the driver at its directory only"() {
        when:
        library.prepare(state)
        Path copy = state.resolve(directory).resolve(name)

        then:
        Files.isRegularFile(copy)
        Files.size(copy) > 100_000
        System.getProperty("org.sqlite.lib.path") == copy.parent.toString()
        System.getProperty("org.sqlite.lib.name") == null
        Files.list(copy.parent).count() == 1

        when: "a second component finds the copy and rewrites nothing"
        def modified = Files.getLastModifiedTime(copy)
        new NativeLibrary().prepare(state)

        then:
        Files.getLastModifiedTime(copy) == modified
    }

    void "the copy is byte for byte the library bundled with the driver"() {
        given:
        byte[] bundled = NativeLibrary.getResourceAsStream(LibraryLoaderUtil.getNativeLibResourcePath() + "/" + name).bytes

        when:
        library.prepare(state)

        then:
        Files.readAllBytes(NativeLibrary.file(state)) == bundled
    }

    void "a component prepares once per process: the first state directory is the one loaded from"() {
        given:
        Path other = Files.createTempDirectory("other")

        when:
        library.prepare(state)
        library.prepare(other)

        then:
        Files.exists(NativeLibrary.file(state))
        !Files.exists(NativeLibrary.file(other))
        System.getProperty("org.sqlite.lib.path") == NativeLibrary.file(state).parent.toString()
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

    void "a damaged copy does not lock the database: a fresh process falls back to the driver's own extraction"() {
        given: "a library file that is not a library at all, where the store expects one"
        Path copy = NativeLibrary.file(state)
        Files.createDirectories(copy.parent)
        Files.writeString(copy, "not a shared library")

        when: "a new process, so the driver has not loaded anything yet, opens a store there"
        Process process = new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-cp", System.getProperty("java.class.path"),
                OpenStoreMain.name, state.toString())
                .redirectErrorStream(true).start()
        String output = process.inputStream.text
        boolean finished = process.waitFor(2, TimeUnit.MINUTES)

        then:
        finished
        process.exitValue() == 0
        output.trim().endsWith("seq=1")
        Files.readString(copy) == "not a shared library"
    }
}
