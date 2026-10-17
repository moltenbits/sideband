package com.moltenbits.sideband.install

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** Asking an executable a hook names for its version, without ever leaving it running. */
class VersionProbeSpec extends Specification {

    Path dir = Files.createTempDirectory("probe")
    Path target = dir.resolve("sideband")
    Long pid

    def cleanup() {
        if (pid != null) {
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    void script(String body) {
        Files.writeString(target, "#!/bin/sh\n$body\n")
        target.toFile().setExecutable(true)
    }

    void "an answer longer than any version line is no version, even when it begins like one"() {
        given:
        script("printf '%s' 'sideband 1.2.${'3' * 400}'")

        when:
        VersionProbe.Result result = new VersionProbe(Duration.ofSeconds(2)).probe(target)

        then:
        result.version() == null
        result.failure() == "gave no version: it printed no version"
    }

    void "the longest answer that fits is still read whole"() {
        given:
        String version = "1.2." + "3" * (256 - "sideband 1.2.".length() - 1)
        script("echo 'sideband $version'")

        expect:
        new VersionProbe(Duration.ofSeconds(2)).probe(target).version() == version
    }

    void "interrupting a probe stops the executable it started, and keeps the thread interrupted"() {
        given:
        Path pidFile = dir.resolve("pid")
        script("echo \$\$ > '$pidFile'\nexec sleep 10")
        AtomicReference<VersionProbe.Result> result = new AtomicReference<>()
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>()
        Thread worker = new Thread({
            result.set(new VersionProbe(Duration.ofSeconds(5)).probe(target))
            stillInterrupted.set(Thread.currentThread().isInterrupted())
        } as Runnable)

        when:
        worker.start()
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        pid = Files.readString(pidFile).trim().toLong()
        worker.interrupt()
        worker.join(3000)

        then:
        !worker.alive
        result.get().failure() == "gave no version: interrupted"
        stillInterrupted.get()
        !ProcessHandle.of(pid).map { it.isAlive() }.orElse(false)
    }

    void "a probe that times out leaves nothing running"() {
        given:
        Path pidFile = dir.resolve("pid")
        script("echo \$\$ > '$pidFile'\nexec sleep 10")

        when:
        VersionProbe.Result result = new VersionProbe(Duration.ofMillis(300)).probe(target)
        pid = Files.readString(pidFile).trim().toLong()

        then:
        result.failure() == "gave no version: it timed out"
        !ProcessHandle.of(pid).map { it.isAlive() }.orElse(false)
    }
}
