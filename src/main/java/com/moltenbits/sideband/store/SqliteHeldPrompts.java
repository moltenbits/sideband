package com.moltenbits.sideband.store;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.HeldPrompts;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** One row per role and session in the {@code held_prompts} table; adoption deletes it and inserts the entry in the same transaction. */
@Singleton
class SqliteHeldPrompts implements HeldPrompts {

    private final Database database;
    private final HeldPromptRows rows;
    private final SqliteJournal journal;

    SqliteHeldPrompts(Database database, HeldPromptRows rows, SqliteJournal journal) {
        this.database = database;
        this.rows = rows;
        this.journal = journal;
    }

    @Override
    public void hold(Path stateDirectory, Role role, String sessionId, String prompt) {
        database.write(stateDirectory, () -> {
            find(role, sessionId).ifPresentOrElse(
                    existing -> rows.update(new HeldPromptRow(existing.id(), role.id(), sessionId, prompt)),
                    () -> rows.save(new HeldPromptRow(null, role.id(), sessionId, prompt)));
            return null;
        });
    }

    @Override
    public void drop(Path stateDirectory, Role role, String sessionId) {
        database.write(stateDirectory, () -> {
            find(role, sessionId).ifPresent(rows::delete);
            return null;
        });
    }

    @Override
    public Optional<String> held(Path stateDirectory, Role role, String sessionId) {
        return database.read(stateDirectory, Optional.empty(), () -> find(role, sessionId).map(HeldPromptRow::prompt));
    }

    @Override
    public Optional<Entry> adopt(Path stateDirectory, Role role, String sessionId, Function<String, Draft> draft) {
        return database.write(stateDirectory, () -> {
            Optional<HeldPromptRow> held = find(role, sessionId);
            held.ifPresent(rows::delete);
            return held.map(row -> journal.insert(draft.apply(row.prompt())));
        });
    }

    /** The session's hold for the role on the current connection. */
    private Optional<HeldPromptRow> find(Role role, String sessionId) {
        return rows.findByRoleAndSessionId(role.id(), sessionId);
    }
}
