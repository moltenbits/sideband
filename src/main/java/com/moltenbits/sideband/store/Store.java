package com.moltenbits.sideband.store;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The one database that holds Sideband's state for every project: the projects, the entries,
 * and each instance's session record. Everything else about the store is behind the {@code Journal} and
 * {@code Sessions} interfaces it implements; this is the view {@code init} creates and
 * {@code doctor} reports.
 */
public interface Store {

    /**
     * The database file name inside the state directory. Not {@code sideband.db}: an earlier
     * version run from the home directory itself kept that directory's own journal there, and
     * it is left alone.
     */
    String FILE_NAME = "journal.db";

    /**
     * Creates the database at the current schema when the state directory holds none, or
     * brings an older one up to date, and reports its health. Safe to repeat.
     */
    StoreHealth create(Path stateDirectory);

    /** The database's health, or empty when the state directory holds none yet. */
    Optional<StoreHealth> inspect(Path stateDirectory);
}
