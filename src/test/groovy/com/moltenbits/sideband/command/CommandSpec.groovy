package com.moltenbits.sideband.command

import com.moltenbits.sideband.SidebandCommand
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import picocli.CommandLine
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the real command tree against a Micronaut context with captured output. Each feature
 * gets its own home directory, and with it its own journal under {@code .sideband}.
 */
abstract class CommandSpec extends Specification {

    Path home = Files.createTempDirectory("sideband-home")

    @AutoCleanup ApplicationContext context = ApplicationContext.run(["sideband.home-directory": home.toString()])

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
