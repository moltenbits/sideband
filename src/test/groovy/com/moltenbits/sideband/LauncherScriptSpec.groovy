package com.moltenbits.sideband

import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/** scripts/sideband: runs the newest Sideband installed, so this repository's hooks name no absolute path. */
class LauncherScriptSpec extends Specification {

    static final Path LAUNCHER = Path.of("scripts/sideband").toAbsolutePath()
    static final String SYSTEM_PATH = "/usr/bin:/bin"

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Path root = Files.createTempDirectory("launcher with spaces")
    Path home = Files.createDirectories(root.resolve("home"))
    Path brew = Files.createDirectories(root.resolve("brew prefix"))
    Map<String, String> env = [HOME: home.toString(), HOMEBREW_PREFIX: brew.toString(), PATH: SYSTEM_PATH]

    static String reported(String version) {
        "echo 'sideband $version (protocol v1)'"
    }

    /** A stand-in Sideband: answers --version with the given commands, otherwise reports its name, arguments and stdin. */
    Path fake(Path dir, String name, String version, int exit = 0) {
        Files.createDirectories(dir)
        Path executable = dir.resolve("sideband")
        Files.writeString(executable, """#!/bin/sh
if [ "\$1" = --version ]; then
${version}
exit 0
fi
echo "ran $name"
for a in "\$@"; do echo "[\$a]"; done
cat
exit $exit
""")
        executable.toFile().setExecutable(true)
        executable
    }

    Path dev(String version) {
        fake(home.resolve(".local/bin"), "dev", reported(version))
    }

    Path brewed(String version) {
        fake(brew.resolve("bin"), "brew", reported(version))
    }

    Map run(List<String> args = ["hook", "prompt"], String stdin = "", Path command = LAUNCHER, Path cwd = root) {
        Process process = new ProcessBuilder([command.toString()] + args).directory(cwd.toFile()).tap {
            environment().clear()
            environment().putAll(env)
        }.start()
        process.outputStream.withWriter { it << stdin }
        String out = process.inputStream.text
        String err = process.errorStream.text
        [exit: process.waitFor(), out: out, err: err]
    }

    String ran(Map result) {
        result.out.readLines().find { it.startsWith("ran ") }?.substring(4)
    }

    void "the launcher is committed executable"() {
        expect:
        Files.isExecutable(LAUNCHER)
    }

    void "the newer of a dev build and a release runs"() {
        given:
        dev(devVersion)
        brewed(brewVersion)

        expect:
        ran(run()) == winner

        where:
        devVersion  | brewVersion || winner
        "1.1.2-dev" | "1.1.1"     || "dev"
        "1.1.1-dev" | "1.1.1"     || "dev"
        "1.1.1-dev" | "1.1.2"     || "brew"
        "1.1.9"     | "1.1.10"    || "brew"
        "1.1.10"    | "1.1.9"     || "dev"
    }

    void "of equal versions the earlier candidate runs, the install directory before Homebrew"() {
        given:
        dev("1.1.1")
        brewed("1.1.1")

        expect:
        ran(run()) == "dev"
    }

    void "the install directory is the one just install uses"() {
        given:
        fake(root.resolve("custom install"), "custom", reported("1.1.2"))
        dev("1.1.1")
        env.SIDEBAND_INSTALL_DIR = root.resolve("custom install").toString()

        expect:
        ran(run()) == "custom"
    }

    void "a single installation runs, wherever it is"() {
        when:
        brewed("1.1.1")

        then:
        ran(run()) == "brew"

        when:
        Files.delete(brew.resolve("bin/sideband"))
        dev("1.1.1-dev")

        then:
        ran(run()) == "dev"
    }

    void "with nothing installed it succeeds silently, so a hook does nothing"() {
        when:
        Map result = run(["hook", "prompt"], '{"prompt":"hi"}')

        then:
        result.exit == 0
        result.out == ""
        result.err == ""
    }

    void "a candidate that does not answer as a supported Sideband version is skipped"() {
        given:
        brewed("1.1.1")
        fake(home.resolve(".local/bin"), "broken", probe)

        expect:
        ran(run()) == "brew"

        where:
        probe << [
                "exit 3",
                "echo 'sideband 9.9.9 (protocol v1)'; exit 1",
                "true",
                "echo 'sideband 9.9.9-beta (protocol v1)'",
                "echo 'sideband 9.9 (protocol v1)'",
                "echo 'something 9.9.9 (protocol v1)'",
                "echo 'sideband 9.9.9 (protocol v1)'; echo 'sideband 9.9.9 (protocol v1)'",
                "printf 'sideband 9.9.9\\n(protocol v1)\\n'"]
    }

    void "probes get no stdin: the hook payload reaches only the Sideband that runs, whole"() {
        given:
        fake(home.resolve(".local/bin"), "dev", "cat > \"\$(dirname \"\$0\")/probe-stdin\"; ${reported('1.1.2-dev')}")
        fake(brew.resolve("bin"), "brew", "cat > \"\$(dirname \"\$0\")/probe-stdin\"; ${reported('1.1.1')}")
        String payload = '{"session_id":"s1","prompt":"two\\nlines"}\n'

        when:
        Map result = run(["hook", "prompt"], payload)

        then:
        ran(result) == "dev"
        result.out.endsWith(payload)
        Files.readString(home.resolve(".local/bin/probe-stdin")) == ""
        Files.readString(brew.resolve("bin/probe-stdin")) == ""
    }

    void "arguments, output and exit status pass through unchanged"() {
        given:
        fake(home.resolve(".local/bin"), "dev", reported("1.1.1"), 7)

        when:
        Map result = run(["append", "a body with spaces", "", "--to", "codex"])

        then:
        result.exit == 7
        result.out.readLines() == ["ran dev", "[append]", "[a body with spaces]", "[]", "[--to]", "[codex]"]
    }

    void "every sideband on PATH is a candidate, in order, so the newest there runs"() {
        given:
        fake(root.resolve("first"), "first", reported("1.1.1"))
        fake(root.resolve("second dir"), "second", reported("1.1.3"))
        fake(root.resolve("third"), "third", reported("1.1.2"))
        env.PATH = [root.resolve("first"), root.resolve("second dir"), root.resolve("third"), SYSTEM_PATH].join(":")

        expect:
        ran(run()) == "second"
    }

    void "the launcher never picks itself, however PATH reaches it"() {
        given: "the launcher's directory first on PATH, and a link to it, then the only real Sideband"
        Path linked = Files.createDirectories(root.resolve("linked"))
        Files.createSymbolicLink(linked.resolve("sideband"), LAUNCHER)
        fake(root.resolve("later"), "later", reported("1.1.1"))
        env.PATH = [LAUNCHER.parent, linked, root.resolve("later"), SYSTEM_PATH].join(":")

        expect: "run by its own path, or through the link"
        ran(run()) == "later"
        ran(run(["hook", "prompt"], "", linked.resolve("sideband"))) == "later"
    }

    void "without HOMEBREW_PREFIX, the conventional Homebrew prefixes are candidates"() {
        given:
        env.remove("HOMEBREW_PREFIX")
        List<String> conventional = ["/opt/homebrew/bin/sideband", "/usr/local/bin/sideband"]
                .findAll { Files.isExecutable(Path.of(it)) }

        when:
        Map result = run(["--version"])

        then: "nothing else is installed for the launcher to find"
        conventional.isEmpty() ? result.out == "" : result.out in conventional.collect { [it, "--version"].execute().text }
    }

    void "each committed Codex hook runs from a subdirectory, through the launcher at the repository's root"() {
        given: "a clone of this repository's hooks and launcher, at a path with spaces"
        Path repo = TempRepo.init(Files.createTempDirectory("a clone with spaces"))
        Files.createDirectories(repo.resolve("scripts"))
        Files.copy(LAUNCHER, repo.resolve("scripts/sideband"))
        Path subdirectory = Files.createDirectories(repo.resolve("src/main"))
        dev("1.1.1")
        Map hooks = context.getBean(ObjectMapper).readValue(Files.readString(Path.of(".codex/hooks.json")), Map).hooks as Map
        List<String> commands = hooks.values().collectMany { groups -> groups.collectMany { it.hooks*.command } }

        expect:
        !commands.isEmpty()
        commands.every { String command ->
            Map result = run(["-c", command], '{"cwd":"x"}\n', Path.of("/bin/sh"), subdirectory)
            List<String> lines = result.out.readLines()
            result.exit == 0 && lines[0] == "ran dev" && lines[1..-2] == command.substring(command.indexOf('" ') + 2).split(" ").collect { "[$it]" } && lines[-1] == '{"cwd":"x"}'
        }
    }
}
