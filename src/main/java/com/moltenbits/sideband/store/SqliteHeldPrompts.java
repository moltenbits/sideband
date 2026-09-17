package com.moltenbits.sideband.store;

import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.HeldPrompts;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** One row per role in the {@code held_prompts} table; adoption deletes it and inserts the entry in the same transaction. */
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
            HeldPromptRow row = new HeldPromptRow(role.id(), sessionId, prompt);
            if (rows.existsById(role.id())) {
                rows.update(row);
            } else {
                rows.save(row);
            }
            return null;
        });
    }

    @Override
    public void drop(Path stateDirectory, Role role) {
        database.write(stateDirectory, () -> {
            rows.deleteById(role.id());
            return null;
        });
    }

    @Override
    public Optional<String> held(Path stateDirectory, Role role, String sessionId) {
        return database.read(stateDirectory, Optional.empty(), () -> rows.findById(role.id())
                .filter(row -> row.sessionId().equals(sessionId))
                .map(HeldPromptRow::prompt));
    }

    @Override
    public Optional<Entry> adopt(Path stateDirectory, Role role, String sessionId, Function<String, Draft> draft) {
        return database.write(stateDirectory, () -> {
            Optional<HeldPromptRow> held = rows.findById(role.id());
            held.ifPresent(row -> rows.deleteById(role.id()));
            return held.filter(row -> row.sessionId().equals(sessionId))
                    .map(row -> journal.insert(draft.apply(row.prompt())));
        });
    }
}
