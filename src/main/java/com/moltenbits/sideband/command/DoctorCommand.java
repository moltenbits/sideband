package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.project.Project;
import com.moltenbits.sideband.project.ProjectLocation;
import com.moltenbits.sideband.project.Projects;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import com.moltenbits.sideband.store.Store;
import io.micronaut.context.annotation.Prototype;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * Reports the state of Sideband and of the current project without printing any message
 * body: versions, paths and permissions, database health, the project, each of its
 * instances' session and pending counts, and the client items. Read by people and by the two agents, which summarize it,
 * so it is prose with one fact per line.
 */
@Command(name = "doctor", description = "Report paths, versions, discussion health, sessions, and skill links", mixinStandardHelpOptions = true)
@Prototype
public class DoctorCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--home", hidden = true, description = "Override the home directory the skills are inspected under")
    Path homeDirectory = Path.of(System.getProperty("user.home"));

    private final SidebandHome home;
    private final Projects projects;
    private final Store store;
    private final Sessions sessions;
    private final Pending pending;
    private final Installer installer;

    DoctorCommand(SidebandHome home, Projects projects, Store store, Sessions sessions, Pending pending, Installer installer) {
        this.home = home;
        this.projects = projects;
        this.store = store;
        this.sessions = sessions;
        this.pending = pending;
        this.installer = installer;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = home.stateDirectory();
        ProjectLocation location = home.locate(repository.directory);
        Optional<Project> project = repository.registered(home, projects);
        InstallReport clients = installer.inspect(homeDirectory, location.root());
        StringBuilder text = new StringBuilder();
        text.append(String.join(" ", spec.root().version())).append('\n');
        text.append("Executable: ").append(clients.executable()).append('\n');
        if (Files.isDirectory(stateDirectory)) {
            text.append("State directory: ").append(stateDirectory)
                    .append(" (").append(permissions(stateDirectory)).append(")\n");
            text.append("Database: ").append(store.inspect(stateDirectory).map(ReportLines::database)
                    .orElse("none; rerun sideband init to create it")).append('\n');
        }
        if (project.isEmpty()) {
            text.append("Not initialized: ").append(location.root())
                    .append(" is not a registered Sideband project. Run sideband init here.\n");
        } else {
            text.append("Project: ").append(project.get().name()).append(" (").append(project.get().path()).append(")\n");
            text.append("\nInstances:\n");
            String name = project.get().name();
            SortedMap<ParticipantId, Session> records = sessions.all(stateDirectory, name);
            SortedSet<ParticipantId> instances = new TreeSet<>(records.keySet());
            Arrays.stream(Role.values()).map(role -> ParticipantId.of(name, role.id())).forEach(instances::add);
            int width = instances.stream().mapToInt(instance -> instance.label(name).length()).max().orElse(0);
            for (ParticipantId instance : instances) {
                Session session = records.get(instance);
                Role client = session != null ? session.client() : instance.name().flatMap(Role::fromId).orElseThrow();
                PendingReport report = pending.report(stateDirectory, instance, client);
                text.append("  ").append(String.format("%-" + width + "s", instance.label(name))).append("  ");
                if (session == null) {
                    text.append("not joined");
                } else {
                    text.append(client.id()).append(" session ").append(session.id())
                            .append(", read up to position ").append(session.offset());
                }
                text.append("; ").append(report.open().size()).append(" open, ")
                        .append(report.inProgress().size()).append(" in progress, ")
                        .append(report.updates().size()).append(report.updates().size() == 1 ? " update, " : " updates, ")
                        .append(report.outgoing().size()).append(" outgoing\n");
            }
        }
        text.append("\nClients:\n");
        ReportLines.clients(text, clients, !"installed".equals(clients.inbound().state()));
        String next = ReportLines.hooksNext(clients);
        if (next != null) {
            text.append('\n').append(next).append('\n');
        }
        PrintWriter out = spec.commandLine().getOut();
        out.print(text);
        out.flush();
        return ExitCode.OK;
    }

    private static String permissions(Path path) throws IOException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException e) {
            return "permissions unknown";
        }
    }
}
