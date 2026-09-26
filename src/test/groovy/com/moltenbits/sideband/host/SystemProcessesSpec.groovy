package com.moltenbits.sideband.host

import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant
import java.time.temporal.ChronoUnit

class SystemProcessesSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    HostProcesses processes = context.getBean(HostProcesses)
    long self = ProcessHandle.current().pid()

    void "the component is exposed only through its interface"() {
        expect:
        processes instanceof SystemProcesses
    }

    void "a running process is described with its start time, to the second"() {
        when:
        HostProcess described = processes.describe(self).get()

        then:
        described.pid() == self
        described.startedAt() == ProcessHandle.current().info().startInstant().get().truncatedTo(ChronoUnit.SECONDS)
        processes.describe(self) == Optional.of(described)
    }

    void "a recorded process is alive only while its id runs with the same start time"() {
        given:
        HostProcess running = processes.describe(self).get()

        expect:
        processes.alive(running)
        !processes.alive(new HostProcess(self, Instant.EPOCH))
        !processes.alive(new HostProcess(Integer.MAX_VALUE, running.startedAt()))
    }

    void "no process is described for an id nothing runs under"() {
        expect:
        processes.describe(Integer.MAX_VALUE).isEmpty()
    }
}
