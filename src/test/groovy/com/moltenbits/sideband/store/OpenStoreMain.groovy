package com.moltenbits.sideband.store

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.journal.Journal
import io.micronaut.context.ApplicationContext

import java.nio.file.Path

/** Opens a store in a fresh JVM and appends one entry; the driver loads its library for the first time here. */
class OpenStoreMain {

    static void main(String[] args) {
        ApplicationContext context = ApplicationContext.run()
        try {
            def entry = context.getBean(Journal).append(Path.of(args[0]), Fixtures.humanDraft("from a fresh process"))
            println "seq=" + entry.seq()
        } finally {
            context.close()
        }
    }
}
