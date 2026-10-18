package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.store.Databases
import io.micronaut.serde.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path

class InitAndDoctorSpec extends CommandSpec {

    Path repo = TempRepo.init()
    /** The home the client skills are installed under, separate from the journal's. */
    Path userHome = Files.createTempDirectory("home")

    Map runJson(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    String text(String... args) {
        stdout = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        stdout.toString()
    }

    void "init creates the state directory, installs both skills and the hook, and append --from operator journals the operator"() {
        when:
        int code = run("init", "--repo", repo.toString(), "--home", userHome.toString())

        then:
        code == ExitCode.OK
        Files.isDirectory(home.resolve(".sideband"))
        Files.isRegularFile(home.resolve(".sideband/sideband.db"))
        !Files.exists(repo.resolve(".git/sideband"))
        stdout.toString().contains("Project: repo (" + repo.toRealPath().resolve(".git") + ")")
        Files.exists(userHome.resolve(".claude/skills/sideband/SKILL.md"))
        Files.exists(userHome.resolve(".agents/skills/sideband/SKILL.md"))
        Files.exists(repo.resolve(".claude/settings.local.json"))
        Files.exists(repo.resolve(".codex/hooks.json"))

        when:
        Map captured = runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        captured.metadata.from == "operator"
        context.getBean(Journal).readAfter(home.resolve(".sideband"), 0).entries()*.body() == ["hello"]
    }

    void "init tells the user to accept pushes in the user settings, never in a repository file that only tightens them"() {
        given: "no user setting, and a repository file holding pushes"
        Files.createDirectories(repo.resolve(".claude"))
        Files.writeString(repo.resolve(".claude/settings.local.json"), '{"crossSessionInbound":"hold"}')

        when:
        int code = run("init", "--repo", repo.toString(), "--home", userHome.toString())
        String step = stdout.toString().readLines().find { it.contains("crossSessionInbound") }

        then:
        code == ExitCode.OK
        step.contains("accept in " + userHome.resolve(".claude/settings.json"))
        step.contains(repo.toRealPath().resolve(".claude/settings.local.json").toString())
    }

    void "append --from operator works without init because nothing about the operator is configured"() {
        when:
        int code = run("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        code == ExitCode.OK
        json().metadata.from == "operator"
    }

    void "outside any git repository the working directory is the project, its hooks land there, and its discussion is in the one journal"() {
        given:
        Path plain = Files.createDirectories(TempRepo.plainDirectory().resolve("nested/work"))

        when:
        int initialised = run("init", "--repo", plain.toString(), "--home", userHome.toString())

        then:
        initialised == ExitCode.OK
        !Files.exists(plain.resolve(".sideband"))
        stdout.toString().contains("Project: work (" + plain.toRealPath() + ")")
        Files.exists(plain.resolve(".claude/settings.local.json"))
        Files.exists(plain.resolve(".codex/hooks.json"))
        !Files.exists(plain.getParent().resolve(".claude"))
        !Files.exists(plain.getParent().resolve(".codex"))

        and: "doctor inspects the same place"
        text("doctor", "--repo", plain.toString(), "--home", userHome.toString()).readLines().find { it.startsWith("  claude hook") }.contains("installed")

        when:
        stdout = new StringWriter()
        int code = run("append", "--from", "operator", "--repo", plain.toString(), "--via", "codex", "--body-file",
                Files.writeString(plain.resolve("p.md"), "no repo here").toString())

        then:
        code == ExitCode.OK
        context.getBean(Journal).readAfter(home.resolve(".sideband"), 0).entries()*.body() == ["no repo here"]
    }

    void "doctor reports an uninitialized repository without failing"() {
        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", userHome.toString())

        then:
        report.readLines()[0] ==~ /sideband \d+\.\d+\.\d+(-dev)?/
        report.contains("Not initialized")
        !report.contains("Instances:")
        report.readLines().find { it.startsWith("  claude skill") }.contains("missing")
        report.readLines().find { it.startsWith("  claude hook") }.contains("missing")
        report.readLines().find { it.startsWith("  claude inbound") }.contains("missing")
        report.contains("can only tighten it")
    }

    void "doctor reports database health, sessions, and pending counts"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file", Files.writeString(repo.resolve("p.md"), "@codex hi").toString())
        runJson("join", "--repo", repo.toString(), "--role", "codex", "--session-id", "s1")

        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", userHome.toString())
        List<String> lines = report.readLines()

        then:
        lines.find { it.startsWith("State directory: ") } == "State directory: " + home.resolve(".sideband") + " (rwx------)"
        lines.find { it.startsWith("Database: ") }.startsWith("Database: " + home.resolve(".sideband/sideband.db") + ", schema 1, 1 entry, ")
        lines.find { it.startsWith("Project: ") } == "Project: repo (" + repo.toRealPath().resolve(".git") + ")"
        lines.find { it.startsWith("Database: ") }.endsWith("integrity ok")
        lines.find { it.startsWith("  codex") }.contains("session s1")
        lines.find { it.startsWith("  codex") }.contains("1 open")
        lines.find { it.startsWith("  claude") }.contains("not joined")
        !report.contains("@codex hi")
    }

    void "doctor lists both clients' own names, joined or not, and every other name with a record, with its client"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        runJson("join", "--repo", repo.toString(), "--role", "claude", "--as", "fable", "--session-id", "f1")

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", userHome.toString()).readLines()
        int start = lines.indexOf("Instances:")

        then:
        lines[start + 1] ==~ /  claude  not joined; .*/
        lines[start + 2] ==~ /  codex   not joined; .*/
        lines[start + 3] ==~ /  fable   claude session f1, read up to position 0; .*/
    }

    void "doctor in a project nobody registered says so, though the journal exists for others"() {
        given:
        run("init", "--repo", TempRepo.named("other").toString(), "--skip-clients")

        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", userHome.toString())

        then:
        report.contains("State directory: " + home.resolve(".sideband"))
        report.contains("Not initialized: " + repo.toRealPath() + " is not a registered Sideband project. Run sideband init here.")
        !report.contains("Instances:")
    }

    void "a database a newer Sideband migrated is reported beside the schema this one supports"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        Databases.execute(home.resolve(".sideband/sideband.db"), "PRAGMA user_version = 99")

        expect:
        text("doctor", "--repo", repo.toString(), "--home", userHome.toString()).readLines()
                .find { it.startsWith("Database: ") }.contains(", schema 99, newer than this Sideband's 1, 0 entries, ")
    }

    static final String EXECUTABLE = ProcessHandle.current().info().command().get()

    void "doctor names the executable and lists each hook's handlers under its line, with what each runs"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", userHome.toString()).readLines()
        int start = lines.findIndexOf { it.startsWith("  claude hook") }

        then:
        lines[1] == "Executable: $EXECUTABLE"
        lines[start].contains(" installed ")
        lines[start + 1..start + 8] == [
                "      UserPromptSubmit",
                "        \"$EXECUTABLE\" hook prompt --agent claude  (this executable)",
                "      SessionStart (matcher clear|compact)",
                "        \"$EXECUTABLE\" hook session-start --agent claude  (this executable)",
                "      Stop",
                "        \"$EXECUTABLE\" hook capture --agent claude  (this executable)",
                "      SessionEnd",
                "        \"$EXECUTABLE\" hook capture --agent claude  (this executable)",
        ]
        lines[start + 9].startsWith("  codex hook")
        !lines.any { it.startsWith("Run sideband init") }
    }

    void "a broken hook says what is wrong on its event's line, and doctor ends by saying init fixes it"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())
        Path hooks = repo.resolve(".codex/hooks.json")
        Map parsed = context.getBean(ObjectMapper).readValue(Files.readString(hooks), Map)
        parsed.hooks.remove("Interrupt")
        parsed.hooks.SessionStart[0].matcher = "startup"
        parsed.hooks.Stop[0].hooks << [type: "command", command: "echo stopped"]
        Files.writeString(hooks, context.getBean(ObjectMapper).writeValueAsString(parsed))

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", userHome.toString()).readLines()
        int start = lines.findIndexOf { it.startsWith("  codex hook") }

        then:
        lines[start].contains(" broken ")
        lines[start + 3..start + 7] == [
                "      SessionStart (matcher clear): under matcher startup, needs clear",
                "        [matcher startup] \"$EXECUTABLE\" hook session-start --agent codex  (this executable)",
                "      Stop",
                "        \"$EXECUTABLE\" hook capture --agent codex  (this executable)",
                "        echo stopped  (another hook; not run)",
        ]
        lines.contains("      Interrupt: no Sideband handler")
        lines.last() == "Run sideband init here to fix the broken hooks."
    }

    void "a hook run through a launcher is custom, and needs nothing done"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())
        Path hooks = repo.resolve(".codex/hooks.json")
        Files.writeString(hooks, Files.readString(hooks).replace("\\\"$EXECUTABLE\\\"", '\\"$(git rev-parse --show-toplevel)/scripts/sideband\\"'))

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", userHome.toString()).readLines()
        int start = lines.findIndexOf { it.startsWith("  codex hook") }

        then:
        lines[start].contains(" custom ")
        lines[start + 2] == '        "$(git rev-parse --show-toplevel)/scripts/sideband" hook prompt --agent codex  (not resolved)'
        !lines.any { it.startsWith("Run sideband init") }
    }

    static final String LAUNCHER = '"$(git rev-parse --show-toplevel)/scripts/sideband"'

    /** Edits a hook file init wrote, through its parsed JSON. */
    void edit(String file, Closure change) {
        ObjectMapper json = context.getBean(ObjectMapper)
        Path hooks = repo.resolve(file)
        Map parsed = json.readValue(Files.readString(hooks), Map)
        change(parsed)
        Files.writeString(hooks, json.writeValueAsString(parsed))
    }

    /** Points every Sideband handler in the Codex file at the launcher. */
    void codexThroughLauncher() {
        Path hooks = repo.resolve(".codex/hooks.json")
        Files.writeString(hooks, Files.readString(hooks).replace("\\\"$EXECUTABLE\\\"", LAUNCHER.replace('"', '\\"')))
    }

    String closing() {
        text("doctor", "--repo", repo.toString(), "--home", userHome.toString()).readLines().last()
    }

    void "a hook file with no Sideband handler ends doctor with the init that installs it"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())
        Files.writeString(repo.resolve(".claude/settings.local.json"), "{}")

        expect:
        closing() == "Run sideband init here to install the missing hooks."
    }

    void "missing and broken hooks share one closing line"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())
        Files.writeString(repo.resolve(".claude/settings.local.json"), "{}")
        edit(".codex/hooks.json") { it.hooks.remove("Interrupt") }

        expect:
        closing() == "Run sideband init here to install the missing hooks and fix the broken ones."
    }

    void "when init would also lose custom Sideband handlers in either file, the closing line counts them"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())
        codexThroughLauncher()
        if (claude == "missing") {
            Files.writeString(repo.resolve(".claude/settings.local.json"), "{}")
        } else {
            edit(".claude/settings.local.json") { it.hooks.remove("Stop") }
        }

        expect:
        closing() == "Run sideband init here to ${action}. It also replaces or removes the 5 custom Sideband handlers " +
                "shown above, registering $EXECUTABLE instead; other hooks are left alone."

        where:
        claude    || action
        "missing" || "install the missing hooks"
        "broken"  || "fix the broken hooks"
    }

    void "a custom handler init would remove as a duplicate or move off the wrong matcher counts, and other hooks do not"() {
        given:
        run("init", "--repo", repo.toString(), "--home", userHome.toString())
        edit(".codex/hooks.json", change)

        expect:
        closing() == "Run sideband init here to fix the broken hooks. It also replaces or removes the 1 custom Sideband " +
                "handler shown above, registering $EXECUTABLE instead; other hooks are left alone."

        where:
        change << [
                { Map hooks ->
                    hooks.hooks.Stop[0].hooks << [type: "command", command: "$LAUNCHER hook capture --agent codex".toString()]
                    hooks.hooks.Stop[0].hooks << [type: "command", command: "echo stopped"]
                },
                { Map hooks ->
                    hooks.hooks.SessionStart[0].matcher = "startup"
                    hooks.hooks.SessionStart[0].hooks[0].command = "$LAUNCHER hook session-start --agent codex".toString()
                },
        ]
    }

    void "init says which executable, and which version, the hooks it registered run"() {
        when:
        List<String> lines = text("init", "--repo", repo.toString(), "--home", userHome.toString()).readLines()
        String version = text("--version").trim()

        then:
        lines.contains("The hooks run $EXECUTABLE (${version}).".toString())
    }
}
