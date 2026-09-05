package com.moltenbits.sideband.command;

import com.moltenbits.sideband.config.Config;
import com.moltenbits.sideband.config.Configs;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.locking.Locks;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Cursor;
import com.moltenbits.sideband.recipient.Pending;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Session;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reports the state of a repository's Sideband installation without printing any message
 * body: paths, permissions, protocol and build versions, configuration, journal health,
 * each role's session and pending counts, lock ownership, and skill links.
 */
@Command(name = "doctor", description = "Report paths, versions, configuration, journal health, sessions, and skill links")
@Prototype
public class DoctorCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    private final SidebandHome home;
    private final Configs configs;
    private final Journal journal;
    private final RecipientState recipients;
    private final ObjectMapper json;

    DoctorCommand(SidebandHome home, Configs configs, Journal journal, RecipientState recipients, ObjectMapper json) {
        this.home = home;
        this.configs = configs;
        this.journal = journal;
        this.recipients = recipients;
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
                Cursor cursor = recipients.load(stateDirectory, role);
                Pending pending = recipients.pending(stateDirectory, role);
                Session session = cursor.session();
                roles.put(role.id(), new RoleReport(
                        session == null ? null : session.id(),
                        session == null ? null : session.isLive(),
                        session == null ? null : session.watermarkEnd(),
                        pending.backlog().size(), pending.live().size(), pending.outgoing().size()));
            }
        }
        Path lockFile = stateDirectory.resolve(Locks.FILE_NAME);
        String lockOwner = Files.exists(lockFile) ? Files.readString(lockFile, UTF_8).strip() : null;
        Output.print(spec, json, new Report(
                spec.root().name() + " " + String.join(" ", spec.root().version()),
                Journal.PROTOCOL_VERSION,
                stateDirectory.toString(),
                exists,
                exists ? permissions(stateDirectory) : null,
                configs.load(stateDirectory).orElse(null),
                health,
                roles,
                lockOwner,
                skillLinks()));
        return ExitCode.OK;
    }

    private static String permissions(Path path) throws IOException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException e) {
            return "unknown";
        }
    }

    /** Where each client expects its skill, and whether that is a link into a checkout of this project. */
    private static List<SkillLink> skillLinks() {
        Path homeDir = Path.of(System.getProperty("user.home"));
        List<SkillLink> links = new ArrayList<>();
        links.add(SkillLink.inspect("claude", homeDir.resolve(".claude/skills/sideband")));
        links.add(SkillLink.inspect("codex", homeDir.resolve(".agents/skills/sideband")));
        return links;
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record Report(String version, String protocol, String stateDirectory, boolean initialized,
                  @Nullable String permissions, @Nullable Config config, @Nullable JournalHealth journal,
                  Map<String, RoleReport> roles, @Nullable String lockOwnerPid, List<SkillLink> skills) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record JournalHealth(long bytes, int entries, int diagnostics, boolean incompleteTail) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record RoleReport(@Nullable String sessionId, @Nullable Boolean sessionLive, @Nullable Long watermarkEnd,
                      int backlog, int live, int outgoing) {
    }

    @Serdeable(naming = SnakeCaseStrategy.class)
    record SkillLink(String client, String path, String state, @Nullable String target) {

        static SkillLink inspect(String client, Path path) {
            try {
                if (Files.isSymbolicLink(path)) {
                    Path target = Files.readSymbolicLink(path);
                    boolean ok = Files.isRegularFile(path.resolve("SKILL.md"));
                    return new SkillLink(client, path.toString(), ok ? "linked" : "broken-link", target.toString());
                }
                if (Files.isDirectory(path)) {
                    return new SkillLink(client, path.toString(), "directory-not-link", null);
                }
                return new SkillLink(client, path.toString(), "missing", null);
            } catch (IOException e) {
                return new SkillLink(client, path.toString(), "unreadable", null);
            }
        }
    }
}
