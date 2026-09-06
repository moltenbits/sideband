package com.moltenbits.sideband

import com.moltenbits.sideband.command.ExitCode
import io.micronaut.context.ApplicationContext
import picocli.CommandLine
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SidebandCommandSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    StringWriter out = new StringWriter()
    StringWriter err = new StringWriter()

    CommandLine cli() {
        CommandLine cli = SidebandCommand.commandLine(context)
        cli.out = new PrintWriter(out)
        cli.err = new PrintWriter(err)
        cli
    }

    void "running without a subcommand prints usage and fails as invalid input"() {
        expect:
        cli().execute() == ExitCode.INVALID_INPUT
        err.toString().contains("Usage: sideband")
    }

    void "help lists every subcommand"() {
        expect:
        cli().execute("--help") == ExitCode.OK
        ["init", "join", "doctor", "capture-human", "append-agent", "pending", "skill", "hook"].every { out.toString().contains(it) }
        !out.toString().contains("mark-delivered")
    }

    void "version reports the build and protocol versions"() {
        expect:
        cli().execute("--version") == ExitCode.OK
        out.toString() ==~ /(?s)sideband \S+ \(protocol v1\).*/
    }
}
