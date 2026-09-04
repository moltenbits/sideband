package com.moltenbits.sideband;

import com.moltenbits.sideband.command.AppendCommand;
import com.moltenbits.sideband.command.ExitCode;
import com.moltenbits.sideband.command.WaitCommand;
import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code sideband} root command. Every operation is a subcommand; because this class is
 * not itself runnable, Picocli reports a missing subcommand as a usage error.
 */
@Command(name = "sideband",
        description = "Local, durable, tridirectional communication between a human, Claude Code, and Codex",
        mixinStandardHelpOptions = true,
        subcommands = {
                AppendCommand.class,
                WaitCommand.class
        })
public class SidebandCommand {

    public static void main(String[] args) {
        int exitCode;
        try (ApplicationContext context = ApplicationContext.builder(Environment.CLI).start()) {
            exitCode = commandLine(context).execute(args);
        }
        System.exit(exitCode);
    }

    /** Builds the command tree with subcommands resolved as Micronaut beans and failures mapped to exit codes. */
    public static CommandLine commandLine(ApplicationContext context) {
        return new CommandLine(SidebandCommand.class, new MicronautFactory(context))
                .setExecutionExceptionHandler(ExitCode.HANDLER);
    }
}
