package com.moltenbits.sideband.host;

import java.util.Optional;

/** The processes running on this machine, as far as telling a client's host process apart needs. */
public interface HostProcesses {

    /** The running process with this id, with when it started; empty when none runs or it cannot be told. */
    Optional<HostProcess> describe(long pid);

    /** True when the recorded process still runs: a process with its id exists and started when it did. */
    default boolean alive(HostProcess process) {
        return describe(process.pid()).filter(process::equals).isPresent();
    }
}
