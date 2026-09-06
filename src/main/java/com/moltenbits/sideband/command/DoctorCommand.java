package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.Installer;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.locking.Locks;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reports the state of a repository's Sideband installation without printing any message
 * body: paths, permissions, protocol and build versions, journal health,
 * each role's session and pending counts, lock ownership, and skill links.
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
    private final Journal journal;
    private final Sessions sessions;
    private final Pending pending;
    private final Installer installer;
    private final ObjectMapper json;

    DoctorCommand(SidebandHome home, Journal journal, Sessions sessions, Pending pending, Installer installer, ObjectMapper json) {
        this.home = home;
        this.journal = journal;
        this.sessions = sessions;
        this.pending = pending;
        this.installer = installer;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        Path stateDirectory = home.locate(repository.directory);
        boolean exists = Files.isDirectory(stateDirectory);
        Path journalFile = stateDirectory.resolve(Journal.FILE_NAME);
        JournalHealth health = null;
        if (Files.exists(journalFile)) {
            Read all = journal.readCompleteFrom(journalFile, 0);
            health = new JournalHealth(Files.size(journalFile), all.entries().size(), all.diagnostics().size(),
                    all.end() < Files.size(journalFile));
        }
        Map<String, RoleReport> roles = new LinkedHashMap<>();
        if (exists) {
            for (Role role : Role.values()) {
                Session session = sessions.load(stateDirectory, role).orElse(null);
                PendingReport report = pending.report(stateDirectory, role);
                roles.put(role.id(), new RoleReport(
                        session == null ? null : session.id(),
                        session == null ? null : session.offset(),
                        report.open().size(), report.inProgress().size(), report.updates().size(), report.outgoing().size()));
            }
        }
        Path lockFile = stateDirectory.resolve(Locks.FILE_NAME);
        String lockOwner = Files.exists(lockFile) ? Files.readString(lockFile, UTF_8).strip() : null;
        Output.print(spec, json, new Report(
                String.join(" ", spec.root().version()),
                Journal.PROTOCOL_VERSION,
                stateDirectory.toString(),
                exists,
                exists ? permissions(stateDirectory) : null,
                health,
                roles,
                lockOwner,
                installer.inspect(homeDirectory, InitCommand.projectRoot(stateDirectory))));
        return ExitCode.OK;
    }

    private static String permissions(Path path) throws IOException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException e) {
            return "unknown";
        }
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Report(String version, String protocol, String stateDirectory, boolean initialized,
                  @Nullable String permissions, @Nullable JournalHealth journal,
                  Map<String, RoleReport> roles, @Nullable String lockOwnerPid, InstallReport clients) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record JournalHealth(long bytes, int entries, int diagnostics, boolean incompleteTail) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record RoleReport(@Nullable String sessionId, @Nullable Long offset,
                      int open, int inProgress, int updates, int outgoing) {
    }
}
