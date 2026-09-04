package com.moltenbits.sideband

import com.moltenbits.sideband.command.ExitCode
import io.micronaut.context.ApplicationContext
import picocli.CommandLine
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SidebandCommandSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    void "running without a subcommand prints usage and fails as invalid input"() {
        given:
        StringWriter err = new StringWriter()
        CommandLine cli = SidebandCommand.commandLine(context)
        cli.err = new PrintWriter(err)

        expect:
        cli.execute() == ExitCode.INVALID_INPUT
        err.toString().contains("Usage: sideband")
    }

    void "help lists the append and wait subcommands"() {
        given:
        StringWriter out = new StringWriter()
        CommandLine cli = SidebandCommand.commandLine(context)
        cli.out = new PrintWriter(out)

        expect:
        cli.execute("--help") == ExitCode.OK
        out.toString().contains("append")
        out.toString().contains("wait")
    }
}
