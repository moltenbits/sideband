package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.InstallReport.Item;
import com.moltenbits.sideband.install.Installer;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Everything a repository needs before the first session: the private state directory,
 * both client skills installed from the executable, and the shared prompt
 * hook registered in both clients' project settings. Host trust is still required. Safe to rerun.
 * <p>
 * A person runs this at a keyboard, so it prints for a person: where the state lives,
 * what was installed where, and what to do next.
 */
@Command(name = "init", description = "Set up this repository: private state, both client skills, and the capture hook", mixinStandardHelpOptions = true)
@Prototype
public class InitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--skip-clients", description = "Only create the state directory")
    boolean skipClients;

    @Option(names = "--home", hidden = true, description = "Override the home directory the skills are installed under")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final SidebandHome home;
    private final Installer installer;

    InitCommand(SidebandHome home, Installer installer) {
        this.home = home;
        this.installer = installer;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = repository.stateDirectory(home);
        InstallReport clients = skipClients ? null : installer.install(homeDirectory, home.projectRoot(stateDirectory));
        PrintWriter out = spec.commandLine().getOut();
        out.print(render(stateDirectory, clients));
        out.flush();
        return ExitCode.OK;
    }

    static String render(Path stateDirectory, @Nullable InstallReport clients) {
        StringBuilder text = new StringBuilder();
        text.append("Sideband is set up in ").append(stateDirectory).append("\n\n");
        if (clients == null) {
            text.append("The client skills and hooks were skipped. Rerun sideband init without --skip-clients to install them.\n");
            return text.toString();
        }
        for (Item skill : clients.skills()) {
            item(text, skill.name() + " skill", skill);
        }
        item(text, "claude hook", clients.hook());
        item(text, "codex hook", clients.codexHook());
        item(text, "claude inbound", clients.inbound());

        List<String> steps = new ArrayList<>();
        steps.add("In Codex, run /hooks and trust the Sideband hook definitions; Codex skips a hook until you do.");
        if (!"installed".equals(clients.inbound().state())) {
            steps.add("Set crossSessionInbound to accept in " + clients.inbound().path()
                    + " (or /config, \"Messages from your other sessions\") so pushes reach Claude Code;"
                    + " until then Claude listens instead.");
        }
        steps.add("Start a discussion: /sideband in Claude Code, $sideband in Codex.");
        text.append("\nNext steps:\n");
        for (int i = 0; i < steps.size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        text.append("\nRerun sideband init any time; sideband doctor reports the current state.\n");
        return text.toString();
    }

    private static void item(StringBuilder text, String label, Item item) {
        text.append(String.format("  %-15s %-11s %s%n", label, item.state(), item.path()));
        if (item.note() != null && !"claude inbound".equals(label)) {
            text.append("      ").append(item.note()).append('\n');
        }
    }
}
