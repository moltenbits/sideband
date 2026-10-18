package com.moltenbits.sideband.store;

import com.moltenbits.sideband.project.Project;
import com.moltenbits.sideband.project.ProjectLocation;
import com.moltenbits.sideband.project.Projects;
import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code projects} table. Registration reads and inserts in one transaction, which SQLite
 * serializes against every other writer, so two projects can never take one name.
 */
@Singleton
class SqliteProjects implements Projects {

    private final Database database;
    private final ProjectRows rows;

    SqliteProjects(Database database, ProjectRows rows) {
        this.database = database;
        this.rows = rows;
    }

    @Override
    public Optional<Project> find(Path stateDirectory, ProjectLocation location) {
        return database.read(stateDirectory, Optional.empty(), () -> rows.findByPath(location.path().toString()).map(SqliteProjects::project));
    }

    @Override
    public Optional<Project> named(Path stateDirectory, String name) {
        return database.read(stateDirectory, Optional.empty(), () -> rows.findByName(name).map(SqliteProjects::project));
    }

    @Override
    public Project register(Path stateDirectory, ProjectLocation location, @Nullable String name) {
        return database.write(stateDirectory, () -> {
            Optional<ProjectRow> registered = rows.findByPath(location.path().toString());
            if (registered.isPresent()) {
                if (name != null && !name.equals(registered.get().name())) {
                    throw new IllegalArgumentException("this project is already registered as " + registered.get().name()
                            + "; a project keeps the name it was registered under");
                }
                return project(registered.get());
            }
            String chosen = name != null ? name : location.defaultName();
            if (chosen == null) {
                throw new IllegalArgumentException("the directory " + location.root() + " gives no usable project name; "
                        + "name the project with sideband init as <project-name>, where a name is " + ParticipantId.NAME_RULE);
            }
            if (!ParticipantId.isName(chosen)) {
                throw new IllegalArgumentException("'" + chosen + "' is not a valid project name: expected " + ParticipantId.NAME_RULE);
            }
            Optional<ProjectRow> holder = rows.findByName(chosen);
            if (holder.isPresent()) {
                throw new IllegalArgumentException("the project name " + chosen + " is taken by " + holder.get().root()
                        + "; name this project with sideband init as <project-name>");
            }
            return project(rows.save(new ProjectRow(null, chosen, location.path().toString(), location.root().toString())));
        });
    }

    private static Project project(ProjectRow row) {
        return new Project(row.name(), Path.of(row.path()), Path.of(row.root()));
    }
}
