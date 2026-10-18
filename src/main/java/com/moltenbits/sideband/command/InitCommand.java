package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.InstallReport.Item;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.project.Project;
import com.moltenbits.sideband.project.Projects;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.store.Store;
import com.moltenbits.sideband.store.StoreHealth;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Everything a project needs before the first session: the private state directory in the
 * home directory with its database at the current schema, the project registered under its
 * directory's name or, with {@code as <project-name>}, under that one, both client skills
 * installed from the executable, and the shared hooks registered in both clients' project
 * settings. Host trust is still required. Safe to rerun; a registered project keeps its name.
 * <p>
 * A person runs this at a keyboard, so it prints for a person: where the state lives,
 * the database it created, what was installed where, and what to do next.
 */
@Command(name = "init", description = "Set up this project: register it, under its directory's name or with `as <project-name>` under that one, and install both client skills and the hooks", mixinStandardHelpOptions = true)
@Prototype
public class InitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--skip-clients", description = "Only create the state directory and register the project")
    boolean skipClients;

    @Parameters(arity = "0..*", paramLabel = "as <project-name>", description = "Register the project under this name rather than its directory's")
    List<String> naming;

    @Option(names = "--home", hidden = true, description = "Override the home directory the skills are installed under")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final SidebandHome home;
    private final Projects projects;
    private final Store store;
    private final Installer installer;

    InitCommand(SidebandHome home, Projects projects, Store store, Installer installer) {
        this.home = home;
        this.projects = projects;
        this.store = store;
        this.installer = installer;
    }

    @Override
    public Integer call() throws IOException {
        String name = name();
        Path stateDirectory = home.initialize();
        StoreHealth database = store.create(stateDirectory);
        Project project = projects.register(stateDirectory, home.locate(repository.directory), name);
        InstallReport clients = skipClients ? null : installer.install(homeDirectory, project.root());
        PrintWriter out = spec.commandLine().getOut();
        out.print(render(stateDirectory, project, database, clients, homeDirectory.resolve(".claude/settings.json"),
                String.join(" ", spec.root().version())));
        out.flush();
        return ExitCode.OK;
    }

    /** The name {@code as <project-name>} gives, or null for the directory's own. */
    private @Nullable String name() {
        if (naming == null || naming.isEmpty()) {
            return null;
        }
        if (naming.size() != 2 || !naming.getFirst().equals("as")) {
            throw new IllegalArgumentException("usage: sideband init as <project-name>, where a name is " + ParticipantId.NAME_RULE);
        }
        return naming.get(1);
    }

    /**
     * @param userSettings the Claude Code user settings file, the only place that can grant acceptance of pushes
     * @param version      this executable's version line, which the hooks it registers run
     */
    static String render(Path stateDirectory, Project project, StoreHealth database, @Nullable InstallReport clients,
                         Path userSettings, String version) {
        StringBuilder text = new StringBuilder();
        text.append("Sideband is set up in ").append(stateDirectory).append('\n');
        text.append("Database: ").append(ReportLines.database(database)).append('\n');
        text.append("Project: ").append(project.name()).append(" (").append(project.path()).append(")\n\n");
        if (clients == null) {
            text.append("The client skills and hooks were skipped. Rerun sideband init without --skip-clients to install them.\n");
            return text.toString();
        }
        ReportLines.clients(text, clients, false);
        text.append("The hooks run ").append(clients.executable()).append(" (").append(version).append(").\n");

        List<String> steps = new ArrayList<>();
        steps.add("In Codex, run /hooks and trust the Sideband hook definitions; Codex skips a hook until you do.");
        Item inbound = clients.inbound();
        if (!"installed".equals(inbound.state())) {
            String step = "Set crossSessionInbound to accept in " + userSettings
                    + " (or /config, \"Messages from your other sessions\") so pushes reach Claude Code;"
                    + " until then Claude listens instead.";
            if (!userSettings.toString().equals(inbound.path())) {
                step += " The repository's " + inbound.path() + " also restricts it (" + inbound.state()
                        + "); a repository file can only tighten the user setting, so loosen it there too.";
            }
            steps.add(step);
        }
        steps.add("Start a discussion: /sideband in Claude Code, $sideband in Codex.");
        text.append("\nNext steps:\n");
        for (int i = 0; i < steps.size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        text.append("\nRerun sideband init any time; sideband doctor reports the current state.\n");
        return text.toString();
    }
}
