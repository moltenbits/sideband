package com.moltenbits.sideband.install

import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/** This repository's own committed hooks, which run Sideband through scripts/sideband. */
class RepositoryHooksSpec extends Specification {

    static final String LAUNCHER = '$(git rev-parse --show-toplevel)/scripts/sideband'

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    void "the committed Codex hooks are what init registers, run through the repository's launcher"() {
        given:
        Installer installer = new ResourceInstaller(context.getBean(ObjectMapper), LAUNCHER)
        Path project = Files.createTempDirectory("project")

        when:
        installer.install(Files.createTempDirectory("home"), project)

        then:
        Files.readString(Path.of(".codex/hooks.json")) == Files.readString(project.resolve(".codex/hooks.json"))
    }

    void "an init in this repository replaces the launcher's handlers with its own path, adding none beside them"() {
        given:
        Path project = Files.createDirectories(Files.createTempDirectory("project").resolve(".codex")).parent
        Files.copy(Path.of(".codex/hooks.json"), project.resolve(".codex/hooks.json"))
        Installer installer = new ResourceInstaller(context.getBean(ObjectMapper), "/opt/sideband/bin/sideband")

        when:
        installer.install(Files.createTempDirectory("home"), project)
        String rewritten = Files.readString(project.resolve(".codex/hooks.json"))

        then:
        !rewritten.contains("scripts/sideband")
        rewritten == Files.readString(Path.of(".codex/hooks.json")).replace(LAUNCHER, "/opt/sideband/bin/sideband")
    }
}
