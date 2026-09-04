package com.moltenbits.sideband.command

import com.moltenbits.sideband.SidebandCommand
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import picocli.CommandLine
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Runs the real command tree against a Micronaut context with captured output. */
abstract class CommandSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    StringWriter stdout = new StringWriter()
    StringWriter stderr = new StringWriter()

    int run(String... args) {
        CommandLine cli = SidebandCommand.commandLine(context)
        cli.out = new PrintWriter(stdout, true)
        cli.err = new PrintWriter(stderr, true)
        cli.execute(args)
    }

    Map json() {
        context.getBean(ObjectMapper).readValue(stdout.toString().trim(), Map)
    }
}
