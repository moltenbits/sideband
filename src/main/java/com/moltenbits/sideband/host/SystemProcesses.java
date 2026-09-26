package com.moltenbits.sideband.host;

import jakarta.inject.Singleton;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** The operating system's own view, through {@link ProcessHandle}. */
@Singleton
class SystemProcesses implements HostProcesses {

    @Override
    public Optional<HostProcess> describe(long pid) {
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(started -> new HostProcess(pid, started.truncatedTo(ChronoUnit.SECONDS)));
    }
}
