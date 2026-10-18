package com.moltenbits.sideband.command

import com.moltenbits.sideband.TempRepo

import java.nio.file.Files
import java.nio.file.Path

/**
 * One journal under the home directory, every project kept apart inside it and reachable
 * as {@code project/@name} (#32).
 */
class CentralJournalSpec extends CommandSpec {

    Path alpha = TempRepo.named("alpha")
    Path beta = TempRepo.named("beta")

    Map runJson(String... args) {
        stdout = new StringWriter()
        stderr = new StringWriter()
        int code = run(args)
        assert code == ExitCode.OK: "exit $code: ${stderr}"
        json()
    }

    int refused(String... args) {
        stdout = new StringWriter()
        stderr = new StringWriter()
        run(args)
    }

    void init(Path repo, String... rest) {
        stdout = new StringWriter()
        stderr = new StringWriter()
        int code = run(["init", "--repo", repo.toString(), "--skip-clients"] + rest.toList() as String[])
        assert code == ExitCode.OK: "exit $code: ${stderr}"
    }

    Path body(Path repo, String text) {
        Files.writeString(Files.createTempFile("body", ".md"), text)
    }

    Map join(Path repo, String client, String session, String name = null) {
        runJson(["join", "--repo", repo.toString(), "--role", client, "--session-id", session]
                + (name ? ["--as", name] : []) as String[])
    }

    /** The operator's words typed into {@code via} in {@code repo}. */
    String prompt(Path repo, String via, String text) {
        runJson("append", "--repo", repo.toString(), "--from", "operator", "--via", via,
                "--body-file", body(repo, text).toString()).metadata.id
    }

    Map pending(Path repo, String instance) {
        runJson("pending", "--repo", repo.toString(), "--role", instance)
    }

    String log(Path repo, String... args) {
        stdout = new StringWriter()
        assert run(["log", "--repo", repo.toString()] + args.toList() as String[]) == ExitCode.OK
        stdout.toString()
    }

    // --- one journal ---

    void "every project's discussion lives in the one database under the home directory, none in the repositories"() {
        when:
        init(alpha)
        init(beta)

        then:
        Files.isRegularFile(home.resolve(".sideband/sideband.db"))
        !Files.exists(alpha.resolve(".git/sideband"))
        !Files.exists(beta.resolve(".git/sideband"))
    }

    void "a worktree is the same project as its repository"() {
        given:
        Path worktree = TempRepo.addWorktree(alpha, "alpha-feature")
        join(alpha, "codex", "s1", "astra")

        expect:
        join(worktree, "claude", "s2").session != null
        prompt(worktree, "astra", "hello from the worktree") != null
        pending(alpha, "astra").open*.entry*.metadata*.via == []
        log(alpha).contains("hello from the worktree")
    }

    // --- project names ---

    void "a project is named after its directory, and init refuses a name another project holds"() {
        given:
        Path other = TempRepo.named("alpha")
        init(alpha)

        expect:
        refused("init", "--repo", other.toString(), "--skip-clients") == ExitCode.INVALID_INPUT
        stderr.toString().contains("sideband init as")
        refused("join", "--repo", other.toString(), "--role", "codex", "--session-id", "s1") == ExitCode.INVALID_INPUT
    }

    void "init as names the project, and the name addresses it from anywhere"() {
        given:
        Path other = TempRepo.named("alpha")
        init(alpha)

        when:
        init(other, "as", "alpha-two")

        then:
        join(other, "codex", "s1", "astra").session.id == "s1"
        join(alpha, "claude", "s2", "opus")
        prompt(alpha, "opus", "go") != null
        runJson("append", "--repo", alpha.toString(), "--from", "opus", "--to", "alpha-two/astra", "--type", "status",
                "--body-file", body(alpha, "fyi").toString()).metadata.to == ["alpha-two/astra"]
    }

    void "init as refuses to rename a project already registered under another name"() {
        given:
        init(alpha)

        expect:
        refused("init", "--repo", alpha.toString(), "--skip-clients", "as", "renamed") == ExitCode.INVALID_INPUT
        refused("init", "--repo", alpha.toString(), "--skip-clients", "as") == ExitCode.INVALID_INPUT
    }

    void "a directory name is normalized into a project name"() {
        given:
        Path spaced = TempRepo.named("My Project_2")
        join(spaced, "codex", "s1", "astra")

        expect:
        prompt(spaced, "astra", "hi") != null
        pending(spaced, "astra").session.id == "s1"
        runJson("append", "--repo", spaced.toString(), "--from", "astra", "--to", "operator", "--type", "status",
                "--body-file", body(spaced, "x").toString()).metadata.from == "my-project-2/astra"
    }

    void "names that collide only after normalization are refused, not merged"() {
        given:
        init(TempRepo.named("Alpha"))

        expect:
        refused("init", "--repo", alpha.toString(), "--skip-clients") == ExitCode.INVALID_INPUT
    }

    // --- identifiers ---

    void "an identifier is the project and the name; the client is on the session record"() {
        when:
        Map joined = join(alpha, "codex", "s1", "astra")

        then:
        joined.session.client == "codex"
        runJson("append", "--repo", alpha.toString(), "--from", "astra", "--to", "operator", "--type", "status",
                "--body-file", body(alpha, "x").toString()).metadata.from == "alpha/astra"
    }

    void "a plain join takes the client's own name as an ordinary name"() {
        when:
        join(alpha, "claude", "s1")

        then:
        runJson("append", "--repo", alpha.toString(), "--from", "claude", "--to", "operator", "--type", "status",
                "--body-file", body(alpha, "x").toString()).metadata.from == "alpha/claude"
    }

    void "a name the other client holds in the project is refused; the same client takes it over"() {
        given:
        join(alpha, "codex", "s1", "opus")

        expect:
        refused("join", "--repo", alpha.toString(), "--role", "claude", "--session-id", "s2", "--as", "opus") == ExitCode.INVALID_INPUT
        stderr.toString().contains("opus")
        join(alpha, "codex", "s3", "opus").replaced == "s1"
        join(beta, "claude", "s4", "opus").session.id == "s4"
    }

    void "all and operator are not names anyone can join as"() {
        expect:
        refused("join", "--repo", alpha.toString(), "--role", "claude", "--session-id", "s1", "--as", "all") == ExitCode.INVALID_INPUT
        refused("join", "--repo", alpha.toString(), "--role", "claude", "--session-id", "s1", "--as", "operator") == ExitCode.INVALID_INPUT
    }

    // --- isolation ---

    void "an entry for a name in one project never reaches the same name in another"() {
        given:
        join(alpha, "codex", "s1")
        join(beta, "codex", "s2")
        join(alpha, "claude", "s3")

        when:
        String id = prompt(alpha, "claude", "@codex review this")

        then:
        pending(alpha, "codex").open*.entry*.metadata*.id == [id]
        pending(beta, "codex").open == []
        !log(beta).contains("review this")
    }

    void "an address to a project nobody registered is refused before anything is written"() {
        given:
        join(alpha, "codex", "s1", "astra")
        String asked = prompt(alpha, "astra", "ask elsewhere")

        expect:
        refused("append", "--repo", alpha.toString(), "--from", "astra", "--to", "nowhere/opus", "--type", "request",
                "--caused-by", asked, "--body-file", body(alpha, "x").toString()) == ExitCode.INVALID_INPUT
        !log(alpha).contains("\nx\n")
    }

    void "@all with nobody joined in the project is refused"() {
        expect:
        refused("append", "--repo", alpha.toString(), "--from", "operator", "--via", "claude",
                "--body-file", body(alpha, "@all hello").toString()) == ExitCode.INVALID_INPUT
        stderr.toString().contains("no joined participants")
    }

    // --- across projects ---

    void "a request to another project's agent is answered back to its sender and seen by neither bystander"() {
        given:
        Path gamma = TempRepo.named("gamma")
        join(alpha, "codex", "a1", "astra")
        join(beta, "codex", "b1", "astra")
        join(gamma, "codex", "g1", "astra")
        String asked = prompt(alpha, "astra", "ask beta's astra about the schema")

        when:
        Map request = runJson("append", "--repo", alpha.toString(), "--from", "astra", "--to", "beta/astra",
                "--type", "request", "--caused-by", asked, "--body-file", body(alpha, "what is the schema?").toString())

        then:
        request.metadata.from == "alpha/astra"
        request.metadata.to == ["beta/astra"]
        pending(beta, "astra").open*.entry*.metadata*.id == [request.metadata.id]
        pending(alpha, "astra").outgoing*.id == [request.metadata.id]
        pending(gamma, "astra").open == []

        when:
        runJson("append", "--repo", beta.toString(), "--from", "astra", "--type", "ack", "--reply-to", request.metadata.id)
        Map reply = runJson("append", "--repo", beta.toString(), "--from", "astra", "--type", "reply",
                "--reply-to", request.metadata.id, "--body-file", body(beta, "it has three tables").toString())

        then:
        reply.metadata.to == ["alpha/astra"]
        Map sender = pending(alpha, "astra")
        sender.outgoing == []
        sender.updates*.metadata*.id.contains(reply.metadata.id)
        pending(beta, "astra").open == []
        pending(beta, "astra").in_progress == []

        and: "both projects list the exchange, the bystander lists none of it"
        log(alpha).contains("what is the schema?")
        log(alpha).contains("it has three tables")
        log(beta).contains("what is the schema?")
        log(beta).contains("it has three tables")
        !log(gamma).contains("schema")

        and: "a fresh join on either side still sees its part"
        join(alpha, "codex", "a2", "astra").outgoing == []
        join(beta, "codex", "b2", "astra").open == []
    }

    void "a trace from one project follows its causes into another"() {
        given:
        join(alpha, "codex", "a1", "astra")
        join(beta, "codex", "b1", "astra")
        String asked = prompt(alpha, "astra", "ask beta")
        String request = runJson("append", "--repo", alpha.toString(), "--from", "astra", "--to", "beta/astra",
                "--type", "request", "--caused-by", asked, "--body-file", body(alpha, "question").toString()).metadata.id

        expect:
        log(beta, "--trace", request).contains("ask beta")
        log(beta, "--id", asked).contains("ask beta")
        !log(beta).contains("ask beta")
    }
}
