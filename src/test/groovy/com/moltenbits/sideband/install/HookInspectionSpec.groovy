package com.moltenbits.sideband.install

import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** What doctor learns about each hook a client needs: every handler in the event, and what each runs. */
class HookInspectionSpec extends Specification {

    static final String THIS = "/opt/sideband/bin/sideband"
    static final List<String> CODEX_EVENTS = ["UserPromptSubmit", "SessionStart", "Stop", "SessionEnd", "Interrupt"]
    static final Map<String, String> SUBCOMMANDS = [UserPromptSubmit: "prompt", SessionStart: "session-start",
                                                    Stop: "capture", SessionEnd: "capture", Interrupt: "capture"]

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    ObjectMapper json = context.getBean(ObjectMapper)
    Installer installer = new ResourceInstaller(json, THIS, Duration.ofMillis(500))
    Path home = Files.createTempDirectory("home")
    Path project = Files.createTempDirectory("project")
    Path bin = Files.createTempDirectory("bin with spaces")
    Path marker = bin.resolve("ran")

    /** A stand-in executable: answers --version with the given commands, and leaves a marker if anything else runs it. */
    Path executable(String name, String version, Path dir = bin) {
        Path file = dir.resolve(name)
        Files.writeString(file, """#!/bin/sh
if [ "\$1" = --version ]; then
echo probed >> "${dir.resolve(name + '.probes')}"
${version}
exit 0
fi
touch "$marker"
""")
        file.toFile().setExecutable(true)
        file
    }

    /** A Codex hooks file registering every event, each with the command {@code command} gives for its subcommand. */
    void codexHooks(Closure<String> command) {
        Map hooks = CODEX_EVENTS.collectEntries { event ->
            Map group = [hooks: [[type: "command", command: command(SUBCOMMANDS[event]).toString()]]]
            if (event == "SessionStart") {
                group = [matcher: "clear"] + group
            }
            [(event): [group]]
        }
        Files.createDirectories(project.resolve(".codex"))
        Files.writeString(project.resolve(".codex/hooks.json"), json.writeValueAsString([hooks: hooks]))
    }

    InstallReport.Item codex() {
        installer.inspect(home, project).codexHook()
    }

    void "every handler in each event the client needs is listed, with what it runs, and only Sideband's count"() {
        given:
        installer.install(home, project)
        Map parsed = json.readValue(Files.readString(project.resolve(".codex/hooks.json")), Map)
        parsed.hooks.UserPromptSubmit << [hooks: [[type: "command", command: "touch '$marker'".toString()]]]
        parsed.hooks.Stop[0].hooks << [type: "command", command: "sideband hook notify --agent codex"]
        Files.writeString(project.resolve(".codex/hooks.json"), json.writeValueAsString(parsed))

        when:
        InstallReport.Item item = codex()

        then:
        item.state() == "installed"
        item.events()*.event() == CODEX_EVENTS
        item.events()*.matcher() == [null, "clear", null, null, null]
        item.events()*.problem() == [null] * 5
        item.events()[0].handlers()*.command() == ["\"$THIS\" hook prompt --agent codex", "touch '$marker'"]
        item.events()[0].handlers()*.runs() == ["this executable", "another hook; not run"]
        item.events()*.handlers().flatten()*.custom().every { !it }
        item.events()[1].handlers()*.matcher() == ["clear"]
        item.events()[2].handlers()*.runs() == ["this executable", "another hook; not run"]
        !Files.exists(marker)
    }

    void "a hook running another installed Sideband by its path is custom, and names that Sideband's version"() {
        given:
        Path other = quoted ? executable("sideband", version) : executable("sideband", version, Files.createTempDirectory("plain"))
        codexHooks { sub -> (quoted ? "\"$other\"" : other.toString()) + " hook $sub --agent codex" }

        when:
        InstallReport.Item item = codex()

        then:
        item.state() == expected
        item.events()*.problem().every { it == problem }
        item.events()*.handlers().flatten()*.runs().every { it == runs }
        item.events()*.handlers().flatten()*.custom().every { it }
        !Files.exists(marker)

        where:
        version                                | quoted || expected | problem                                            | runs
        "echo 'sideband 1.1.1 (protocol v1)'"  | true   || "custom" | null                                               | "sideband 1.1.1"
        "echo 'sideband 1.1.3-dev'"            | true   || "custom" | null                                               | "sideband 1.1.3-dev"
        "echo 'sideband 1.1.2'"                | false  || "custom" | null                                               | "sideband 1.1.2"
    }

    void "a command the shell would expand is custom and never run, nor any path inside it probed"() {
        given:
        Path target = executable("sideband", "echo 'sideband 9.9.9'")
        codexHooks { sub -> form.replace("DIR", target.parent.toString()) + " hook $sub --agent codex" }

        when:
        InstallReport.Item item = codex()

        then:
        item.state() == "custom"
        item.events()*.handlers().flatten()*.runs().every { it == "not resolved" }
        item.events()*.handlers().flatten()*.custom().every { it }
        !Files.exists(marker)
        !Files.exists(bin.resolve("sideband.probes"))

        where:
        form << ['"$(git rev-parse --show-toplevel)/scripts/sideband"', '"$(echo DIR)/sideband"', '"$HOME/bin/sideband"',
                 '"DIR/$NAME/../sideband"']
    }

    void "a path that does not answer as a Sideband breaks every hook that runs it, saying why"() {
        given:
        Path target = setup.call(this)
        codexHooks { sub -> "\"$target\" hook $sub --agent codex" }

        when:
        InstallReport.Item item = codex()

        then:
        item.state() == "broken"
        item.events()*.problem().every { it == problem.replace("TARGET", target.toString()) }
        item.events()*.handlers().flatten()*.custom().every { it }

        where:
        problem                                         | setup
        "TARGET not found"                              | { HookInspectionSpec s -> s.bin.resolve("nothing/sideband") }
        "TARGET not executable"                         | { HookInspectionSpec s -> Files.writeString(s.bin.resolve("sideband"), "plain") }
        "TARGET gave no version: it exited 3"           | { HookInspectionSpec s -> s.executable("sideband", "exit 3") }
        "TARGET gave no version: it printed no version" | { HookInspectionSpec s -> s.executable("sideband", "echo 'sideband 1.1'") }
        "TARGET gave no version: it printed no version" | { HookInspectionSpec s -> s.executable("sideband", "echo 'sideband 1.1.1'; echo more") }
        "TARGET gave no version: it timed out"          | { HookInspectionSpec s -> s.executable("sideband", "sleep 5") }
    }

    void "a probe that fills its output or leaves a child holding it still ends within the timeout"() {
        given:
        Path target = executable("sideband", script)
        codexHooks { sub -> "\"$target\" hook $sub --agent codex" }

        when:
        long started = System.nanoTime()
        InstallReport.Item item = codex()

        then:
        Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(4)
        item.events()[0].problem() == "$target gave no version: $reason"

        where:
        script                                                     | reason
        "yes sideband | head -c 1000000"                           | "it printed no version"
        "(sleep 5 &) ; echo 'sideband 1.1.1'; exec sleep 5"        | "it timed out"
    }

    void "each path is probed once, however many hooks run it"() {
        given:
        Path target = executable("sideband", "echo 'sideband 1.1.1'")
        codexHooks { sub -> "\"$target\" hook $sub --agent codex" }

        when:
        codex()

        then:
        Files.readAllLines(bin.resolve("sideband.probes")) == ["probed"]
    }

    void "a hook naming the other client, or none, is broken whatever it runs"() {
        given:
        codexHooks { sub -> "\"$THIS\" hook $sub" + suffix }

        when:
        InstallReport.Item item = codex()

        then:
        item.state() == "broken"
        item.events()*.problem().every { it == problem }

        where:
        suffix            || problem
        " --agent claude" || "--agent claude, needs codex"
        ""                || "no --agent, needs codex"
    }

    void "each event's own shape is checked: no handler, a handler under another matcher, or more than one"() {
        given:
        Files.createDirectories(project.resolve(".codex"))
        Files.writeString(project.resolve(".codex/hooks.json"), json.writeValueAsString([hooks: [
                UserPromptSubmit: [[hooks: [[type: "command", command: "\"$THIS\" hook prompt --agent codex".toString()],
                                            [type: "command", command: "\"$THIS\" hook prompt --agent codex".toString()]]]],
                SessionStart    : [[matcher: "startup", hooks: [[type: "command", command: "\"$THIS\" hook session-start --agent codex".toString()]]]],
                Stop            : [[hooks: [[type: "command", command: "\"$THIS\" hook capture --agent codex".toString()]]]],
                SessionEnd      : [[hooks: [[type: "command", command: "\"$THIS\" hook capture --agent codex".toString()]]]],
        ]]))

        when:
        InstallReport.Item item = codex()

        then: "the capture handlers shared across Stop and SessionEnd are one per event, not duplicates"
        item.state() == "broken"
        item.events()*.problem() == ["registered 2 times", "under matcher startup, needs clear", null, null, "no Sideband handler"]
        item.events()[1].handlers()*.matcher() == ["startup"]
        item.events()[4].handlers() == []
    }

    void "a file with no Sideband hook at all is missing, and still lists what is there"() {
        given:
        Files.createDirectories(project.resolve(".codex"))
        Files.writeString(project.resolve(".codex/hooks.json"), json.writeValueAsString([hooks: [
                Stop: [[hooks: [[type: "command", command: "echo done"]]]]]]))

        when:
        InstallReport.Item item = codex()

        then:
        item.state() == "missing"
        item.events()[2].handlers()*.command() == ["echo done"]
        item.events()[2].handlers()*.runs() == ["another hook; not run"]
    }

    void "no file means no events to list"() {
        expect:
        codex().state() == "missing"
        codex().events() == []
    }

    void "the report names the executable init registers"() {
        expect:
        installer.inspect(home, project).executable() == THIS
        installer.install(home, project).executable() == THIS
    }
}
