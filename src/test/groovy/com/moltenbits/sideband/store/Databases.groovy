package com.moltenbits.sideband.store

import org.flywaydb.core.Flyway

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Databases made directly, as another Sideband would have left them, and plain SQL against them. */
class Databases {

    /** A database exactly as the schema 4 migrations left it, holding one entry. */
    static void atSchema4(Path file) {
        Flyway.configure().dataSource("jdbc:sqlite:" + file, null, null)
                .locations("classpath:db/migration").target("4").load().migrate()
        assert query(file, "PRAGMA user_version") == "4"
        assert !tables(file).contains("captured_turns")
        execute(file, """INSERT INTO entries (id, created_at, sender, via, recipients, type, route, reply_to, caused_by,
                expects_reply, live, backlog, body) VALUES ('019a', '2026-09-02T16:42:00-05:00', 'operator', 'claude', 'codex',
                'request', 'direct', NULL, NULL, 1, 'auto', 'confirm', 'from schema 4')""")
    }

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
