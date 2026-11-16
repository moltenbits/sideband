package com.moltenbits.sideband.home

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.project.ProjectLocation
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class GitCommonDirHomeSpec extends Specification {

    Path userHome = Files.createTempDirectory("sideband-home")
    SidebandHome home = new GitCommonDirHome(userHome.toString())

    void "the state directory is .sideband in the home directory, whatever the working directory"() {
        expect:
        home.stateDirectory() == userHome.resolve(".sideband")
    }

    void "without a configured home directory, the state lives in the user's own"() {
        expect:
        new GitCommonDirHome("").stateDirectory() == Path.of(System.getProperty("user.home")).resolve(".sideband")
    }

    void "a repository's project is its Git common directory, rooted at the checkout"() {
        given:
        Path repo = TempRepo.init()

        expect:
        home.locate(repo) == new ProjectLocation(repo.toRealPath().resolve(".git"), repo.toRealPath())
    }

    void "a subdirectory of the repository is the same project"() {
        given:
        Path repo = TempRepo.init()
        Path nested = Files.createDirectories(repo.resolve("src/deep"))

        expect:
        home.locate(nested) == home.locate(repo)
    }

    void "every worktree of a repository is the same project"() {
        given:
        Path repo = TempRepo.init()
        Path worktree = TempRepo.addWorktree(repo, "feature")

        expect:
        home.locate(worktree) == home.locate(repo)
        home.locate(worktree).defaultName() == "repo"
    }

    void "a directory outside any repository is its own project, and so is each of its subdirectories"() {
        given:
        Path plain = TempRepo.plainDirectory()
        Path nested = Files.createDirectories(plain.resolve("inner"))

        expect:
        home.locate(plain) == new ProjectLocation(plain.toRealPath(), plain.toRealPath())
        home.locate(nested) == new ProjectLocation(nested.toRealPath(), nested.toRealPath())
    }

    void "locating creates nothing, in the project or the home directory"() {
        given:
        Path repo = TempRepo.init()

        when:
        home.locate(repo)

        then:
        !Files.exists(home.stateDirectory())
        !Files.exists(repo.resolve(".git/sideband"))
    }

    void "initializing creates the state directory readable only by its owner"() {
        when:
        Path directory = home.initialize()

        then:
        directory == home.stateDirectory()
        Files.isDirectory(directory)
        Files.getPosixFilePermissions(directory) == EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    }

    void "initializing an existing state directory is idempotent"() {
        given:
        Path first = home.initialize()

        expect:
        home.initialize() == first
    }
}
