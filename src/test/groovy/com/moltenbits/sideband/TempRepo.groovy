package com.moltenbits.sideband

import java.nio.file.Files
import java.nio.file.Path

/** Real temporary Git repositories for tests, so nothing mocks git rev-parse. */
class TempRepo {

    static Path init(Path parent = Files.createTempDirectory("sideband")) {
        Path repo = Files.createDirectories(parent.resolve("repo"))
        git(repo, "init", "-q")
        git(repo, "-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
                "commit", "-q", "--allow-empty", "-m", "initial")
        repo
    }

    static Path addWorktree(Path repo, String name) {
        Path worktree = repo.resolveSibling(name)
        git(repo, "worktree", "add", "-q", "-b", name, worktree.toString())
        worktree
    }

    static Path plainDirectory() {
        Files.createTempDirectory("sideband-plain")
    }

    static void git(Path cwd, String... args) {
        Process process = new ProcessBuilder(["git"] + args.toList()).directory(cwd.toFile()).redirectErrorStream(true).start()
        String output = process.inputStream.text
        assert process.waitFor() == 0: "git ${args.join(' ')} failed: $output"
    }
}
