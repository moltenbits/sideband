package com.moltenbits.sideband.locking

import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class FileLocksSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Locks locks = context.getBean(Locks)
    Path directory = Files.createTempDirectory("locks")
    Path lockFile = directory.resolve(Locks.FILE_NAME)

    void "the lock is exposed only through its interface"() {
        expect:
        locks instanceof FileLocks
    }

    void "acquiring creates the lock file with the owner's pid and releasing removes it"() {
        when:
        Lock held = locks.acquire(directory)

        then:
        Files.readString(lockFile) == ProcessHandle.current().pid().toString()

        when:
        held.close()

        then:
        !Files.exists(lockFile)
    }

    void "a lock left by a dead process is reclaimed"() {
        given:
        Files.writeString(lockFile, "999999999")

        when:
        Lock held = locks.acquire(directory)

        then:
        Files.readString(lockFile) == ProcessHandle.current().pid().toString()

        cleanup:
        held.close()
    }

    void "a lock held by a live process blocks until the timeout"() {
        given:
        Files.writeString(lockFile, ProcessHandle.current().pid().toString())

        when:
        LockFile.acquire(lockFile, Duration.ofMillis(200))

        then:
        thrown(LockTimeoutException)
        Files.exists(lockFile)
    }

    void "an unreadable lock is reclaimed only after the grace period"() {
        given:
        Files.writeString(lockFile, "not a pid")

        when:
        LockFile.acquire(lockFile, Duration.ofMillis(200))

        then:
        thrown(LockTimeoutException)

        when:
        Files.setLastModifiedTime(lockFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000))
        Lock held = LockFile.acquire(lockFile, Duration.ofMillis(200))

        then:
        Files.readString(lockFile) == ProcessHandle.current().pid().toString()

        cleanup:
        held?.close()
    }
}
