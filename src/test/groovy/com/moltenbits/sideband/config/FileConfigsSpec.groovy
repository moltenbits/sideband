package com.moltenbits.sideband.config

import com.moltenbits.sideband.TempRepo
import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class FileConfigsSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    Configs configs = context.getBean(Configs)
    Path repo = TempRepo.init()
    Path state = Files.createDirectories(repo.resolve(".git/sideband"))

    void "the component is exposed only through its interface"() {
        expect:
        configs instanceof FileConfigs
    }

    void "slugs are lowercase ascii identifiers"() {
        expect:
        FileConfigs.slug(name) == slug

        where:
        name               | slug
        "James Hardwick"   | "james-hardwick"
        "  Élodie Durand " | "elodie-durand"
        "j.doe_2"          | "j.doe_2"
        "---"              | null
        ""                 | null
        null               | null
    }

    void "initialization derives the human from git user.name and tightens the file"() {
        given:
        TempRepo.git(repo, "config", "user.name", "James Hardwick")

        when:
        Config config = configs.initialize(state, repo, null)

        then:
        config == new Config(1, new HumanIdentity("james-hardwick", "James Hardwick"))
        configs.load(state).get() == config
        Files.readString(state.resolve("config.json")).contains('"human":{"id":"james-hardwick","display_name":"James Hardwick"}')
        Files.getPosixFilePermissions(state.resolve("config.json")) == EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }

    void "an explicit identifier wins and the git name is still the display name"() {
        given:
        TempRepo.git(repo, "config", "user.name", "James Hardwick")

        expect:
        configs.initialize(state, repo, "james").human() == new HumanIdentity("james", "James Hardwick")
    }

    void "initialization is idempotent and never overwrites"() {
        given:
        configs.initialize(state, repo, "james")

        expect:
        configs.initialize(state, repo, "someone-else").human().id() == "james"
    }

    void "an empty identifier is invalid input that names both ways to supply one"() {
        when:
        configs.initialize(state, repo, "")

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("--human")
        e.message.contains("user.name")
    }

    void "an invalid identifier is rejected before anything is written"() {
        when:
        configs.initialize(state, repo, "James")

        then:
        thrown(com.moltenbits.sideband.protocol.InvalidEntryException)
        !Files.exists(state.resolve("config.json"))
    }

    void "require explains how to fix a missing configuration"() {
        when:
        configs.require(state)

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("sideband init")
    }
}
