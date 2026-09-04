package com.moltenbits.sideband.home

import com.moltenbits.sideband.TempRepo
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class GitCommonDirHomeSpec extends Specification {

    SidebandHome home = new GitCommonDirHome()

    void "state directory lives beneath the repository's .git directory"() {
        given:
        Path repo = TempRepo.init()

        expect:
        home.locate(repo) == repo.toRealPath().resolve(".git/sideband")
    }

    void "a subdirectory of the repository resolves to the same state directory"() {
        given:
        Path repo = TempRepo.init()
        Path nested = Files.createDirectories(repo.resolve("src/deep"))

        expect:
        home.locate(nested) == home.locate(repo)
    }

    void "every worktree of a repository shares one state directory"() {
        given:
        Path repo = TempRepo.init()
        Path worktree = TempRepo.addWorktree(repo, "feature")

        expect:
        home.locate(worktree) == home.locate(repo)
    }

    void "locating does not create the directory"() {
        given:
        Path repo = TempRepo.init()

        when:
        Path directory = home.locate(repo)

        then:
        !Files.exists(directory)
    }

    void "initializing creates the directory readable only by its owner"() {
        given:
        Path repo = TempRepo.init()

        when:
        Path directory = home.initialize(repo)

        then:
        Files.isDirectory(directory)
        Files.getPosixFilePermissions(directory) == EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    }

    void "initializing an existing directory is idempotent"() {
        given:
        Path repo = TempRepo.init()
        Path first = home.initialize(repo)

        expect:
        home.initialize(repo) == first
    }

    void "a directory outside any repository is rejected"() {
        given:
        Path plain = TempRepo.plainDirectory()

        when:
        home.locate(plain)

        then:
        NotARepositoryException e = thrown()
        e.message.contains(plain.toString())
    }
}
