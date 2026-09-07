package com.moltenbits.sideband.store;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The one database that holds a repository's Sideband state: the entries and each role's
 * session record. Everything else about the store is behind the {@code Journal} and
 * {@code Sessions} interfaces it implements; this is the view {@code doctor} reports.
 */
public interface Store {

    /** The database file name inside the state directory. */
    String FILE_NAME = "sideband.db";

    /** The database's health, or empty when the state directory holds none yet. */
    Optional<StoreHealth> inspect(Path stateDirectory);
}
