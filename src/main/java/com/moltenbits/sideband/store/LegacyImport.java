package com.moltenbits.sideband.store;

import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Carries a discussion recorded before the store existed into a new database: every
 * complete entry of {@code journal.md}, in order, and each role's session record from
 * {@code sessions/<role>.json}, with its byte offsets turned into positions. It runs once,
 * inside the first write's transaction, so a database is never half imported.
 * The old files are left where they are; nothing reads them again while the database
 * exists.
 */
@Singleton
class LegacyImport {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyImport.class);
    private static final String SESSIONS_DIRECTORY = "sessions";

    private final ObjectMapper json;
    private final LegacyJournal journal;
    private final EntryRows entries;
    private final SessionRows sessions;

    LegacyImport(ObjectMapper json, EntryRows entries, SessionRows sessions) {
        this.json = json;
        this.journal = new LegacyJournal(json);
        this.entries = entries;
        this.sessions = sessions;
    }

    /** True when the state directory holds a journal the store has not absorbed yet. */
    boolean present(Path stateDirectory) {
        return Files.isRegularFile(stateDirectory.resolve(LegacyJournal.FILE_NAME));
    }

    /**
     * Imports what is there, on the calling thread's open transaction, into a database that
     * holds no entries yet. Once anything is in the table, or when there is no journal,
     * nothing happens; a second process that waited on the first one's lock finds the table
     * full and does the same.
     */
    void run(Path stateDirectory) {
        Path file = stateDirectory.resolve(LegacyJournal.FILE_NAME);
        if (!Files.isRegularFile(file) || entries.count() > 0) {
            return;
        }
        LegacyJournal.Parsed parsed;
        try {
            parsed = journal.parse(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        for (String problem : parsed.problems()) {
            LOG.warn("skipped part of {}: {}", file, problem);
        }
        for (LegacyJournal.LegacyEntry entry : parsed.entries()) {
            SqliteJournal.insert(entries, entry.metadata(), entry.body());
        }
        for (Role role : Role.values()) {
            session(stateDirectory, role).ifPresent(session -> SqliteSessions.save(sessions, role,
                    new Session(session.id(), session.startedAt(),
                            position(parsed.entries(), session.watermark()),
                            position(parsed.entries(), session.offset()),
                            session.resumed())));
        }
        LOG.info("imported {} entries from {}", parsed.entries().size(), file);
    }

    /** The position of the last entry that ended at or before a byte offset: what that offset meant. */
    static long position(List<LegacyJournal.LegacyEntry> entries, long offset) {
        long position = 0;
        for (LegacyJournal.LegacyEntry entry : entries) {
            if (entry.end() > offset) {
                break;
            }
            position++;
        }
        return position;
    }

    private Optional<Session> session(Path stateDirectory, Role role) {
        Path file = stateDirectory.resolve(SESSIONS_DIRECTORY).resolve(role.id() + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readString(file, UTF_8), Session.class));
        } catch (IOException | RuntimeException e) {
            LOG.warn("skipped {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
