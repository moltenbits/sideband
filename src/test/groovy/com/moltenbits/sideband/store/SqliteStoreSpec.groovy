package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class SqliteStoreSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Store store = context.getBean(Store)
    Journal journal = context.getBean(Journal)
    Path dir = Files.createTempDirectory("store")
    Path file = dir.resolve(Store.FILE_NAME)

    void "creating the store writes an empty database at the current schema, and inspecting reports it"() {
        expect:
        store.inspect(dir).isEmpty()

        when:
        StoreHealth created = store.create(dir)

        then:
        Files.isRegularFile(dir.resolve(Store.FILE_NAME))
        created.path() == dir.resolve(Store.FILE_NAME).toString()
        created.entries() == 0
        created.bytes() > 0
        created.integrity() == "ok"
        created.schema() == Database.SCHEMA_VERSION
        created.migratedFrom() == null
        created.supported() == Database.SCHEMA_VERSION
        store.inspect(dir).get() == created
    }

    void "creating again keeps what is there"() {
        given:
        store.create(dir)
        journal.append(dir, Fixtures.humanDraft("kept"))

        when:
        StoreHealth again = store.create(dir)

        then:
        again.entries() == 1
        journal.readAfter(dir, 0).entries()*.body() == ["kept"]
    }

    void "inspecting a database an older Sideband left behind migrates it and says from which schema"() {
        given:
        schema4()

        when:
        StoreHealth health = store.inspect(dir).get()

        then:
        health.schema() == Database.SCHEMA_VERSION
        health.migratedFrom() == 4
        tables().contains("captured_turns")
        journal.readAfter(dir, 0).entries()*.body() == ["from schema 4"]

        and: "once current, it was not migrated again"
        store.inspect(dir).get().migratedFrom() == null
    }

    void "creating over an older database reports the migration, though its own inspection then finds it current"() {
        given:
        schema4()

        expect:
        store.create(dir).migratedFrom() == 4
        store.create(dir).migratedFrom() == null
    }

    void "a database a newer Sideband migrated is reported at its schema, beside the one this executable supports, and left alone"() {
        given:
        store.create(dir)
        sql("PRAGMA user_version = 99")

        when:
        StoreHealth health = store.inspect(dir).get()

        then:
        health.schema() == 99
        health.supported() == Database.SCHEMA_VERSION
        health.migratedFrom() == null
        query("PRAGMA user_version") == "99"
    }

    void "a database another process has created but not yet migrated reads as absent, and reading leaves no file behind where there was none"() {
        given:
        Path empty = Files.createTempDirectory("store")
        sql("CREATE TABLE half_made (x INTEGER)")

        expect:
        store.inspect(empty).isEmpty()
        !Files.exists(empty.resolve(Store.FILE_NAME))
        store.inspect(dir).isEmpty()
    }

    private void schema4() {
        Databases.atSchema4(file)
    }

    private List<String> tables() {
        Databases.tables(file)
    }

    private String query(String text) {
        Databases.query(file, text)
    }

    private void sql(String text) {
        Databases.execute(file, text)
    }
}
