package com.moltenbits.sideband.store

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Databases made directly, as another Sideband would have left them, and plain SQL against them. */
class Databases {

    static List<String> tables(Path file) {
        withConnection(file) { it.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").with { r ->
            List<String> names = []
            while (r.next()) { names << r.getString(1) }
            names
        } }
    }

    static String query(Path file, String text) {
        withConnection(file) { it.createStatement().executeQuery(text).with { r -> r.next() ? r.getString(1) : null } }
    }

    static void execute(Path file, String text) {
        withConnection(file) { it.createStatement().execute(text) }
    }

    private static <T> T withConnection(Path file, Closure<T> work) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)
        try {
            work(connection)
        } finally {
            connection.close()
        }
    }
}
