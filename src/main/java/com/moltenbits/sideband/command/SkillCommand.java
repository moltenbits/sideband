package com.moltenbits.sideband.command;

import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.context.annotation.Prototype;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Prints the adapter instructions for the calling client. The installed skill files are
 * stubs that run this, so updating the executable updates what each client does.
 */
@Command(name = "skill", description = "Print the adapter instructions for the calling client", mixinStandardHelpOptions = true)
@Prototype
public class SkillCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--client", hidden = true, description = "Override the client detected from the environment")
    Role client;

    private final HostEnvironment host;
    private final Installer installer;

    SkillCommand(HostEnvironment host, Installer installer) {
        this.host = host;
        this.installer = installer;
    }

    @Override
    public Integer call() throws IOException {
        Role role = client != null ? client : host.requireRole("--client");
        spec.commandLine().getOut().print(installer.instructions(role));
        spec.commandLine().getOut().flush();
        return ExitCode.OK;
    }
}
