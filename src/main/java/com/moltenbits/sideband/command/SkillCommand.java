package com.moltenbits.sideband.command;

import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.protocol.Role;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Prints the adapter instructions for the calling client. The installed skill files are
 * stubs that run this, so updating the executable updates what each client does. With
 * {@code --eject} the instructions are written into the installed SKILL.md instead, for
 * the operator to edit; that skill then stops updating with the executable.
 */
@Command(name = "skill", description = "Print the adapter instructions for the calling client, or eject them into its SKILL.md to edit", mixinStandardHelpOptions = true)
@Prototype
public class SkillCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--client", hidden = true, description = "Override the client detected from the environment")
    Role client;

    @Option(names = "--eject", description = "Write the instructions into the installed SKILL.md so you can edit them. "
            + "That skill then no longer updates when the executable does; delete the file and rerun `sideband init` to go back.")
    boolean eject;

    @Option(names = "--home", hidden = true, description = "Override the home directory the skill is installed under")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final HostEnvironment host;
    private final Installer installer;
    private final ObjectMapper json;

    SkillCommand(HostEnvironment host, Installer installer, ObjectMapper json) {
        this.host = host;
        this.installer = installer;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Role role = client != null ? client : host.requireRole("--client");
        if (eject) {
            Output.print(spec, json, installer.eject(homeDirectory, role));
            return ExitCode.OK;
        }
        spec.commandLine().getOut().print(installer.instructions(role));
        spec.commandLine().getOut().flush();
        return ExitCode.OK;
    }
}
