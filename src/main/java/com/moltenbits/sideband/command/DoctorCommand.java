package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import com.moltenbits.sideband.store.Store;
import com.moltenbits.sideband.store.StoreHealth;
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
import java.util.concurrent.Callable;

/**
 * Reports the state of a repository's Sideband installation without printing any message
 * body: versions, paths and permissions, database health, each role's session and pending
 * counts, and the client items. Read by people and by the two agents, which summarize it,
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
    private final Store store;
    private final Sessions sessions;
    private final Pending pending;
    private final Installer installer;

    DoctorCommand(SidebandHome home, Store store, Sessions sessions, Pending pending, Installer installer) {
        this.home = home;
        this.store = store;
        this.sessions = sessions;
        this.pending = pending;
        this.installer = installer;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = home.locate(repository.directory);
        boolean exists = Files.isDirectory(stateDirectory);
        StringBuilder text = new StringBuilder();
        text.append(String.join(" ", spec.root().version())).append('\n');
        if (!exists) {
            text.append("Not initialized: no state directory at ").append(stateDirectory)
                    .append(". Run sideband init here.\n");
        } else {
            text.append("State directory: ").append(stateDirectory)
                    .append(" (").append(permissions(stateDirectory)).append(")\n");
            text.append("Database: ").append(store.inspect(stateDirectory).map(DoctorCommand::database)
                    .orElse("none yet; the first entry creates it")).append('\n');
            text.append("\nRoles:\n");
            for (Role role : Role.values()) {
                Session session = sessions.load(stateDirectory, role).orElse(null);
                PendingReport report = pending.report(stateDirectory, role);
                text.append(String.format("  %-7s ", role.id()));
                if (session == null) {
                    text.append("not joined");
                } else {
                    text.append("session ").append(session.id()).append(", read up to position ").append(session.offset());
                }
                text.append("; ").append(report.open().size()).append(" open, ")
                        .append(report.inProgress().size()).append(" in progress, ")
                        .append(report.updates().size()).append(report.updates().size() == 1 ? " update, " : " updates, ")
                        .append(report.outgoing().size()).append(" outgoing\n");
            }
        }
        InstallReport clients = installer.inspect(homeDirectory, home.projectRoot(stateDirectory));
        text.append("\nClients:\n");
        InstallLines.append(text, clients, !"installed".equals(clients.inbound().state()));
        PrintWriter out = spec.commandLine().getOut();
        out.print(text);
        out.flush();
        return ExitCode.OK;
    }

    private static String database(StoreHealth health) {
        return health.path() + ", " + health.entries() + (health.entries() == 1 ? " entry, " : " entries, ")
                + health.bytes() + " bytes, integrity " + health.integrity();
    }

    private static String permissions(Path path) throws IOException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException e) {
            return "permissions unknown";
        }
    }
}
