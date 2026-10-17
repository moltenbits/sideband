package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo
import com.moltenbits.sideband.journal.Journal
import com.moltenbits.sideband.store.Databases
import io.micronaut.serde.ObjectMapper

import java.nio.file.Files
import java.nio.file.Path

class InitAndDoctorSpec extends CommandSpec {

    Path repo = TempRepo.init()
    Path home = Files.createTempDirectory("home")

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
        int code = run("init", "--repo", repo.toString(), "--home", home.toString())

        then:
        code == ExitCode.OK
        Files.isDirectory(repo.resolve(".git/sideband"))
        Files.isRegularFile(repo.resolve(".git/sideband/sideband.db"))
        Files.exists(home.resolve(".claude/skills/sideband/SKILL.md"))
        Files.exists(home.resolve(".agents/skills/sideband/SKILL.md"))
        Files.exists(repo.resolve(".claude/settings.local.json"))
        Files.exists(repo.resolve(".codex/hooks.json"))

        when:
        Map captured = runJson("append", "--from", "operator", "--repo", repo.toString(), "--via", "claude", "--body-file",
                Files.writeString(repo.resolve("p.md"), "hello").toString())

        then:
        captured.metadata.from == "operator"
        context.getBean(Journal).readAfter(repo.resolve(".git/sideband"), 0).entries()*.body() == ["hello"]
    }

    void "init tells the user to accept pushes in the user settings, never in a repository file that only tightens them"() {
        given: "no user setting, and a repository file holding pushes"
        Files.createDirectories(repo.resolve(".claude"))
        Files.writeString(repo.resolve(".claude/settings.local.json"), '{"crossSessionInbound":"hold"}')

        when:
        int code = run("init", "--repo", repo.toString(), "--home", home.toString())
        String step = stdout.toString().readLines().find { it.contains("crossSessionInbound") }

        then:
        code == ExitCode.OK
        step.contains("accept in " + home.resolve(".claude/settings.json"))
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

    void "outside any git repository the state lives in a .sideband directory in the working directory, and the hooks land there too"() {
        given:
        Path plain = Files.createDirectories(TempRepo.plainDirectory().resolve("nested/work"))

        when:
        int initialised = run("init", "--repo", plain.toString(), "--home", home.toString())

        then:
        initialised == ExitCode.OK
        Files.isDirectory(plain.resolve(".sideband"))
        Files.exists(plain.resolve(".claude/settings.local.json"))
        Files.exists(plain.resolve(".codex/hooks.json"))
        !Files.exists(plain.getParent().resolve(".claude"))
        !Files.exists(plain.getParent().resolve(".codex"))

        and: "doctor inspects the same place"
        text("doctor", "--repo", plain.toString(), "--home", home.toString()).readLines().find { it.startsWith("  claude hook") }.contains("installed")

        when:
        stdout = new StringWriter()
        int code = run("append", "--from", "operator", "--repo", plain.toString(), "--via", "codex", "--body-file",
                Files.writeString(plain.resolve("p.md"), "no repo here").toString())

        then:
        code == ExitCode.OK
        context.getBean(Journal).readAfter(plain.resolve(".sideband"), 0).entries()*.body() == ["no repo here"]
    }

    void "doctor reports an uninitialized repository without failing"() {
        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", home.toString())

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
        String report = text("doctor", "--repo", repo.toString(), "--home", home.toString())
        List<String> lines = report.readLines()

        then:
        lines.find { it.startsWith("State directory: ") }.endsWith(repo.toRealPath().resolve(".git/sideband").toString() + " (rwx------)")
        lines.find { it.startsWith("Database: ") }.startsWith("Database: " + repo.toRealPath().resolve(".git/sideband/sideband.db").toString() + ", schema 5, 1 entry, ")
        lines.find { it.startsWith("Database: ") }.endsWith("integrity ok")
        lines.find { it.startsWith("  codex") }.contains("session s1")
        lines.find { it.startsWith("  codex") }.contains("1 open")
        lines.find { it.startsWith("  claude") }.contains("not joined")
        !report.contains("@codex hi")
    }

    void "doctor lists both unnamed instances, joined or not, and every named one with a record"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        runJson("join", "--repo", repo.toString(), "--role", "claude", "--as", "fable", "--session-id", "f1")

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", home.toString()).readLines()
        int start = lines.indexOf("Instances:")

        then:
        lines[start + 1] ==~ /  claude        not joined; .*/
        lines[start + 2] ==~ /  claude:fable  session f1, read up to position 0; .*/
        lines[start + 3] ==~ /  codex         not joined; .*/
    }

    void "doctor brings a database an older Sideband left up to date, and says it did, without asking for init"() {
        given:
        Path database = Files.createDirectories(repo.resolve(".git/sideband")).resolve("sideband.db")
        Databases.atSchema4(database)

        when:
        String report = text("doctor", "--repo", repo.toString(), "--home", home.toString())

        then:
        String line = report.readLines().find { it.startsWith("Database: ") }
        line.contains(", schema 5, migrated from 4, 1 entry, ")
        !line.contains("init")
        Databases.query(database, "PRAGMA user_version") == "5"

        and: "the next look finds it current"
        text("doctor", "--repo", repo.toString(), "--home", home.toString()).readLines()
                .find { it.startsWith("Database: ") }.contains(", schema 5, 1 entry, ")
    }

    void "init reports the migration it applied to an older database"() {
        given:
        Databases.atSchema4(Files.createDirectories(repo.resolve(".git/sideband")).resolve("sideband.db"))

        expect:
        text("init", "--repo", repo.toString(), "--skip-clients").readLines()
                .find { it.startsWith("Database: ") }.contains(", schema 5, migrated from 4, 1 entry, ")
    }

    void "a database a newer Sideband migrated is reported beside the schema this one supports"() {
        given:
        run("init", "--repo", repo.toString(), "--skip-clients")
        Databases.execute(repo.resolve(".git/sideband/sideband.db"), "PRAGMA user_version = 99")

        expect:
        text("doctor", "--repo", repo.toString(), "--home", home.toString()).readLines()
                .find { it.startsWith("Database: ") }.contains(", schema 99, newer than this Sideband's 5, 0 entries, ")
    }

    static final String EXECUTABLE = ProcessHandle.current().info().command().get()

    void "doctor names the executable and lists each hook's handlers under its line, with what each runs"() {
        given:
        run("init", "--repo", repo.toString(), "--home", home.toString())

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", home.toString()).readLines()
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
        run("init", "--repo", repo.toString(), "--home", home.toString())
        Path hooks = repo.resolve(".codex/hooks.json")
        Map parsed = context.getBean(ObjectMapper).readValue(Files.readString(hooks), Map)
        parsed.hooks.remove("Interrupt")
        parsed.hooks.SessionStart[0].matcher = "startup"
        parsed.hooks.Stop[0].hooks << [type: "command", command: "echo stopped"]
        Files.writeString(hooks, context.getBean(ObjectMapper).writeValueAsString(parsed))

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", home.toString()).readLines()
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
        lines.last() == "Run sideband init here to fix the hooks marked above; it points them at this executable."
    }

    void "a hook run through a launcher is custom, and needs nothing done"() {
        given:
        run("init", "--repo", repo.toString(), "--home", home.toString())
        Path hooks = repo.resolve(".codex/hooks.json")
        Files.writeString(hooks, Files.readString(hooks).replace("\\\"$EXECUTABLE\\\"", '\\"$(git rev-parse --show-toplevel)/scripts/sideband\\"'))

        when:
        List<String> lines = text("doctor", "--repo", repo.toString(), "--home", home.toString()).readLines()
        int start = lines.findIndexOf { it.startsWith("  codex hook") }

        then:
        lines[start].contains(" custom ")
        lines[start + 2] == '        "$(git rev-parse --show-toplevel)/scripts/sideband" hook prompt --agent codex  (not resolved)'
        !lines.any { it.startsWith("Run sideband init") }
    }

    void "init says which executable, and which version, the hooks it registered run"() {
        when:
        List<String> lines = text("init", "--repo", repo.toString(), "--home", home.toString()).readLines()
        String version = text("--version").trim()

        then:
        lines.contains("The hooks run $EXECUTABLE (${version}).".toString())
    }
}
