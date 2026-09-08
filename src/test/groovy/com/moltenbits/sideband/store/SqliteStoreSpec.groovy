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
}
