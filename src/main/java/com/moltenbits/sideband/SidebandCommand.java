package com.moltenbits.sideband;

import com.moltenbits.sideband.command.JoinCommand;
import com.moltenbits.sideband.command.AppendCommand;
import com.moltenbits.sideband.command.DoctorCommand;
import com.moltenbits.sideband.command.ExitCode;
import com.moltenbits.sideband.command.HookCommand;
import com.moltenbits.sideband.command.InitCommand;
import com.moltenbits.sideband.command.LogCommand;
import com.moltenbits.sideband.command.PendingCommand;
import com.moltenbits.sideband.command.SkillCommand;
import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import com.moltenbits.sideband.journal.Journal;
import picocli.CommandLine.IVersionProvider;

/**
 * The {@code sideband} root command. Every operation is a subcommand; because this class is
 * not itself runnable, Picocli reports a missing subcommand as a usage error.
 */
@Command(name = "sideband",
        description = "Local, durable, tridirectional communication between a human, Claude Code, and Codex",
        mixinStandardHelpOptions = true,
        versionProvider = SidebandCommand.Version.class,
        subcommands = {
                InitCommand.class,
                JoinCommand.class,
                AppendCommand.class,
                PendingCommand.class,
                LogCommand.class,
                SkillCommand.class,
                HookCommand.class,
                DoctorCommand.class
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
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(ExitCode.HANDLER);
    }

    /** Reports the build version, which Gradle generates into {@link BuildVersion}. */
    static final class Version implements IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {"sideband " + BuildVersion.VERSION + " (protocol "
                    + Journal.PROTOCOL_VERSION + ")"};
        }
    }
}
